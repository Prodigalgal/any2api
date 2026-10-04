"""Verify caller-owned Agent loops, complete context and WEB bridges with the official SDK."""

import argparse
import concurrent.futures
import datetime
import json
import sys
import time
from pathlib import Path
from uuid import uuid4

from web_context_probe import MODELS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-manifest", type=Path, required=True)
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--base-url")
    parser.add_argument(
        "--providers", nargs="+", choices=sorted(MODELS), default=list(MODELS)
    )
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--runtime-version", required=True)
    parser.add_argument("--require-resource-schema", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise SystemExit("Report already exists; use a fresh path")
    if args.sdk_path:
        sys.path.insert(0, str(args.sdk_path.resolve()))
    import httpx
    import openai

    manifest = json.loads(args.key_manifest.read_text(encoding="utf-8-sig"))
    base_url = args.base_url or manifest["base_url"]
    started_at = datetime.datetime.now(datetime.UTC).isoformat()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    readiness_url = httpx.URL(base_url).copy_with(path="/readyz")
    readiness = httpx.get(readiness_url, timeout=10)
    if readiness.status_code != 200:
        args.report.write_text(
            json.dumps(
                {
                    "runtime_version": args.runtime_version,
                    "started_at": started_at,
                    "passed": False,
                    "blocked_by": "gateway_readiness",
                    "readiness_status": readiness.status_code,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        raise SystemExit(
            "Gateway is not ready; provider inference checks were not started"
        )

    def verify(provider: str) -> dict:
        model = f"{provider}/{MODELS[provider]}"
        rows = []
        requests = []
        stored_ids = []
        state = {}
        business_number = str(10000 + int(uuid4().hex[:5], 16) % 80000)
        document_name = f"交付资料-{business_number}.txt"
        review_rule = "逐页核对后汇总"
        system = f"你是文档验收助手。本次文档名称是 {document_name}。使用用户提供的技能完成验收。"
        skill = f"""# SKILL.md: 文档验收
适用任务：核对文档并汇报验收结果。
1. 没有工具结果时，调用 review_document；document_name 取 system 中的文档名称。
2. review_rule 固定为「{review_rule}」；batch_number 取最早用户消息给出的业务批次号。
3. 工具由客户端执行。收到结果后不再调用工具，用一句话汇报文档名称、规则、批次号、
   工具返回的 status 和 processed_pages。不得自行推算或编造工具返回值。
"""
        history = [
            {
                "role": "user",
                "content": f"本次业务批次号为 {business_number}，后续继续使用。",
            }
        ]
        for turn in range(18):
            history.extend(
                [
                    {
                        "role": "assistant",
                        "content": "已记录，继续保留当前文档和批次。",
                    },
                    {
                        "role": "user",
                        "content": f"登记第 {turn + 1} 条检查记录，文档和批次保持不变。",
                    },
                ]
            )
        history.append({"role": "user", "content": "请按照当前文档验收技能处理任务。"})
        chat_messages = [
            {"role": "system", "content": system},
            {"role": "developer", "content": skill},
            *history,
        ]
        response_input = [{"role": "developer", "content": skill}, *history]
        function = {
            "type": "function",
            "name": "review_document",
            "strict": True,
            "description": "核对指定文档；参数按照上下文中的文档信息、验收技能和业务批次填写。",
            "parameters": {
                "type": "object",
                "properties": {
                    "document_name": {"type": "string"},
                    "review_rule": {"type": "string"},
                    "batch_number": {"type": "string"},
                },
                "required": ["document_name", "review_rule", "batch_number"],
                "additionalProperties": False,
            },
        }
        expected_arguments = {
            "document_name": document_name,
            "review_rule": review_rule,
            "batch_number": business_number,
        }
        result_content = json.dumps(
            {"status": "已核验", "processed_pages": 37}, ensure_ascii=False
        )
        chat_tool = {
            "type": "function",
            "function": {
                name: value for name, value in function.items() if name != "type"
            },
        }

        def capture(response, phase):
            request_id = response._request_id
            requests.append({"phase": phase, "request_id": request_id})
            return request_id

        def record(name, operation):
            started = time.monotonic()
            request_count = len(requests)
            try:
                row = {"check": name, "passed": True, **(operation() or {})}
            except openai.APIStatusError as error:
                detail = error.body if isinstance(error.body, dict) else {}
                detail = detail.get("error", detail)
                row = {
                    "check": name,
                    "passed": False,
                    "http_status": error.status_code,
                    "error_code": detail.get("code"),
                    "request_id": error.request_id,
                }
            except (
                httpx.HTTPError,
                AssertionError,
                ValueError,
                TypeError,
                KeyError,
                RuntimeError,
            ) as error:
                row = {
                    "check": name,
                    "passed": False,
                    "exception_type": type(error).__name__,
                }
                if isinstance(error, AssertionError):
                    row["failed_assertion"] = str(error)
                if callable(getattr(error, "errors", None)):
                    row["schema_errors"] = error.errors(
                        include_input=False, include_url=False
                    )
            except openai.APIError as error:
                detail = error.body if isinstance(error.body, dict) else {}
                detail = detail.get("error", detail)
                row = {
                    "check": name,
                    "passed": False,
                    "exception_type": type(error).__name__,
                    "error_code": detail.get("code"),
                }
            if len(requests) > request_count:
                row.setdefault("request_id", requests[-1]["request_id"])
            row.update(provider=provider, seconds=round(time.monotonic() - started, 3))
            rows.append(row)
            snapshot = {
                "provider": provider,
                "model": model,
                "runtime_version": args.runtime_version,
                "checks": rows,
                "requests": requests,
                "history_messages": len(history),
                "stored_response_ids": stored_ids,
            }
            partial = args.report.with_name(f"{args.report.stem}-{provider}.json")
            partial.write_text(
                json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print(json.dumps(row, ensure_ascii=False), flush=True)

        def verify_arguments(arguments):
            parsed = json.loads(arguments)
            assert parsed == expected_arguments, f"context arguments mismatch: {parsed}"

        def verify_summary(text):
            for value in (document_name, review_rule, business_number, "已核验", "37"):
                assert value in text, (
                    f"missing business field: {value}; synthetic reply: {text}"
                )

        with openai.OpenAI(
            base_url=base_url,
            api_key=manifest["keys"][provider]["api_key"],
            timeout=args.timeout,
            max_retries=0,
        ) as client:

            def model_contract():
                model_detail = client.models.retrieve(model)
                assert model_detail.id == model and model_detail.owned_by == provider
                assert model_detail.capabilities["tools"]["strict"] is True
                assert (
                    model_detail.capabilities["tools"]["strict_validation"]
                    == "gateway_schema_validation"
                )
                assert (
                    model_detail.parameter_adaptation["function_tools"]["executor"]
                    == "client"
                )
                return {"tool_executor": "client"}

            record("models.retrieve/Agent contract", model_contract)

            def chat_call():
                first = client.chat.completions.create(
                    model=model,
                    messages=chat_messages,
                    tools=[chat_tool],
                    tool_choice="required",
                    parallel_tool_calls=False,
                )
                request_id = capture(first, "Chat function call")
                message = first.choices[0].message
                calls = message.tool_calls or []
                assert first.choices[0].finish_reason == "tool_calls", (
                    "Chat missing tool_calls finish"
                )
                assert len(calls) == 1 and calls[0].function.name == function["name"], (
                    "Chat wrong calls"
                )
                verify_arguments(calls[0].function.arguments)
                assert calls[0].id, "Chat missing call ID"
                state["chat_message"] = message.model_dump(exclude_none=True)
                state["chat_call_id"] = calls[0].id
                return {
                    "request_id": request_id,
                    "system_skill_history_arguments": True,
                    "calls": 1,
                }

            record("Chat/JSON/system/skill/early history/strict function", chat_call)

            def chat_replay():
                assert "chat_call_id" in state, "Chat initial call unavailable"
                with client.chat.completions.with_streaming_response.create(
                    model=model,
                    messages=[
                        *chat_messages,
                        state["chat_message"],
                        {
                            "role": "tool",
                            "tool_call_id": state["chat_call_id"],
                            "content": result_content,
                        },
                    ],
                    tools=[chat_tool],
                    tool_choice="none",
                    stream=True,
                    stream_options={"include_usage": True},
                ) as raw:
                    request_id = raw.headers.get("x-request-id")
                    requests.append(
                        {"phase": "Chat function result", "request_id": request_id}
                    )
                    chunks = list(raw.parse())
                text = "".join(
                    chunk.choices[0].delta.content or ""
                    for chunk in chunks
                    if chunk.choices
                )
                assert not any(
                    choice.delta.tool_calls
                    for chunk in chunks
                    for choice in chunk.choices
                ), "Chat none produced another tool call"
                terminals = [
                    choice.finish_reason
                    for chunk in chunks
                    for choice in chunk.choices
                    if choice.finish_reason is not None
                ]
                assert terminals == ["stop"], f"Chat terminals: {terminals}"
                assert chunks[-1].usage, "Chat final usage missing"
                verify_summary(text)
                return {
                    "request_id": request_id,
                    "call_id_replayed": True,
                    "SSE_terminal_usage": True,
                }

            record("Chat/SSE/client result/full history/skill summary", chat_replay)

            def response_call():
                with client.responses.stream(
                    model=model,
                    instructions=system,
                    input=response_input,
                    tools=[function],
                    tool_choice={"type": "function", "name": function["name"]},
                    parallel_tool_calls=False,
                    store=True,
                ) as stream:
                    request_id = stream._response.headers.get("x-request-id")
                    requests.append(
                        {"phase": "Responses function call", "request_id": request_id}
                    )
                    events = list(stream)
                    for event in events:
                        if event.type in {
                            "response.completed",
                            "response.failed",
                            "response.incomplete",
                        }:
                            stored_ids.append(event.response.id)
                    failures = [
                        event for event in events if event.type == "response.failed"
                    ]
                    assert not failures, (
                        f"Responses failed: {failures[0].response.error if failures else ''}"
                    )
                    first = stream.get_final_response()
                assert first.status == "completed", (
                    f"Responses status={first.status}, error={first.error}"
                )
                calls = [item for item in first.output if item.type == "function_call"]
                assert len(calls) == 1 and calls[0].name == function["name"], (
                    "Responses wrong calls"
                )
                verify_arguments(calls[0].arguments)
                assert calls[0].call_id, "Responses missing call ID"
                deltas = [
                    event.delta
                    for event in events
                    if event.type == "response.function_call_arguments.delta"
                ]
                assert "".join(deltas) == calls[0].arguments, (
                    "Responses argument accumulation mismatch"
                )
                assert (
                    sum(
                        event.type == "response.function_call_arguments.done"
                        for event in events
                    )
                    == 1
                )
                assert sum(event.type == "response.completed" for event in events) == 1
                assert not any(
                    event.type in {"response.failed", "response.incomplete"}
                    for event in events
                )
                state["response"] = first
                state["response_call_id"] = calls[0].call_id
                return {
                    "request_id": request_id,
                    "system_skill_history_arguments": True,
                    "SSE_arguments": True,
                }

            record(
                "Responses/SSE/instructions/skill/early history/strict function",
                response_call,
            )

            def response_replay():
                assert "response" in state, "Responses initial call unavailable"
                first = state["response"]
                follow = client.responses.create(
                    model=model,
                    instructions=system,
                    store=False,
                    tools=[function],
                    tool_choice="none",
                    input=[
                        *response_input,
                        *[item.model_dump(exclude_none=True) for item in first.output],
                        {
                            "type": "function_call_output",
                            "call_id": state["response_call_id"],
                            "output": result_content,
                        },
                    ],
                )
                request_id = capture(follow, "Responses full function result")
                assert follow.status == "completed", (
                    f"Responses replay status={follow.status}"
                )
                assert not any(item.type == "function_call" for item in follow.output)
                verify_summary(follow.output_text)
                return {
                    "request_id": request_id,
                    "call_id_replayed": True,
                    "full_history_replayed": True,
                }

            record(
                "Responses/JSON/client result/full history/skill summary",
                response_replay,
            )

            def response_continuation():
                assert "response" in state, "Responses initial call unavailable"
                first = state["response"]
                assert client.responses.retrieve(first.id).id == first.id
                follow = client.responses.create(
                    model=model,
                    instructions=system,
                    previous_response_id=first.id,
                    store=True,
                    tools=[function],
                    tool_choice="none",
                    input=[
                        {
                            "type": "function_call_output",
                            "call_id": state["response_call_id"],
                            "output": result_content,
                        }
                    ],
                )
                stored_ids.append(follow.id)
                request_id = capture(follow, "Responses stored function result")
                assert follow.status == "completed", (
                    f"Continuation status={follow.status}"
                )
                verify_summary(follow.output_text)
                page = client.responses.input_items.list(
                    follow.id, limit=100, order="asc"
                )
                dumped = [
                    item.model_dump(
                        exclude_none=True,
                        warnings="error" if args.require_resource_schema else False,
                    )
                    for item in page.data
                ]
                if args.require_resource_schema:
                    from openai.types.responses import ResponseItem
                    from pydantic import TypeAdapter

                    for item in dumped:
                        TypeAdapter(ResponseItem).validate_python(item)
                assert any(
                    item.get("call_id") == state["response_call_id"] for item in dumped
                )
                return {
                    "request_id": request_id,
                    "previous_response_id": True,
                    "resources": True,
                    "resource_schema_verified": args.require_resource_schema,
                }

            record(
                "Responses/state/client result/skill summary/resources",
                response_continuation,
            )

            def cleanup():
                for response_id in stored_ids:
                    try:
                        client.responses.delete(response_id)
                    except openai.NotFoundError:
                        # Strict failures can return an ID before any state is committed.
                        pass
                    try:
                        client.responses.retrieve(response_id)
                        raise AssertionError("deleted response remained visible")
                    except openai.NotFoundError:
                        pass
                return {"deleted_owned_test_responses": len(stored_ids)}

            record("Responses/owned synthetic state cleanup", cleanup)

        return {
            "provider": provider,
            "model": model,
            "checks": rows,
            "requests": requests,
            "passed": all(row["passed"] for row in rows),
        }

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(verify, args.providers))
    report = {
        "sdk_version": openai.__version__,
        "base_url": base_url,
        "runtime_version": args.runtime_version,
        "started_at": started_at,
        "finished_at": datetime.datetime.now(datetime.UTC).isoformat(),
        "providers": results,
        "passed": all(result["passed"] for result in results),
    }
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps({"passed": report["passed"], "providers": len(results)}), flush=True
    )
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
