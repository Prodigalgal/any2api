"""Exercise an authorized gateway with the official SDK; --fixture enables deterministic assertions."""

import argparse
import json
import os
import sys
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument(
        "--timeout", type=float, default=30, help="Client read timeout in seconds."
    )
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument(
        "--common", action="store_true",
        help="Test common tool formats without optional model controls.",
    )
    parser.add_argument(
        "--core",
        action="store_true",
        help="Test common Chat/Responses function APIs only.",
    )
    parser.add_argument(
        "--no-reasoning",
        action="store_true",
        help="Omit reasoning for models that do not support it.",
    )
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.sdk_path:
        sys.path.insert(0, str(args.sdk_path.resolve()))
    import openai
    from openai import OpenAI

    key = os.environ.get("ANY2API_E2E_API_KEY")
    if not key:
        raise SystemExit("Set ANY2API_E2E_API_KEY to an authorized test key.")
    client = OpenAI(
        api_key=key, base_url=args.base_url, timeout=args.timeout, max_retries=0
    )
    checks = []

    def passed(name: str) -> None:
        checks.append(name)
        print("PASS", name, flush=True)

    if args.core or args.common:
        assert client.models.list().data
        passed("models discovery")

    text = client.responses.create(
        model=args.model,
        input="Reply with a short confirmation.",
        store=False,
        **(
            {}
            if args.core or args.common
            else {
                "include": ["reasoning.encrypted_content"],
                "text": {"verbosity": "low"},
                "extra_body": {"client_metadata": {"client": "any2api-sdk-smoke"}},
                **({} if args.no_reasoning else {"reasoning": {"summary": "auto"}}),
            }
        ),
    )
    assert text.status == "completed" and text.output_text
    passed("responses nonstream/client fields")
    started = time.monotonic()
    first_delta = None
    with client.responses.stream(
        model=args.model, input="Reply with a short confirmation.", store=False
    ) as stream:
        for event in stream:
            if event.type == "response.output_text.delta" and first_delta is None:
                first_delta = time.monotonic() - started
        final = stream.get_final_response()
    elapsed = time.monotonic() - started
    assert final.status == "completed" and final.output_text and first_delta is not None
    if args.fixture:
        assert elapsed - first_delta >= 0.02, (elapsed, first_delta)
    passed("responses SDK stream accumulation/incremental delivery")

    function = {
        "type": "function",
        "name": "inspect_workspace",
        "description": "Return a synthetic directory listing.",
        "strict": False,
        "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    }
    first = client.responses.create(
        model=args.model,
        input="Use inspect_workspace to inspect the directory, then report the exact filename from its result.",
        tools=[function],
        tool_choice="required",
        store=True,
    )
    calls = [item for item in first.output if item.type == "function_call"]
    assert calls and all(call.name == "inspect_workspace" for call in calls)
    second = client.responses.create(
        model=args.model,
        previous_response_id=first.id,
        input=[
            {
                "type": "function_call_output",
                "call_id": call.call_id,
                "output": "demo.txt",
            }
            for call in calls
        ],
        store=True,
    )
    assert second.status == "completed" and second.output_text
    if args.core or args.common:
        assert "demo.txt" in second.output_text
    assert client.responses.retrieve(first.id).id == first.id
    page = client.responses.input_items.list(second.id, limit=1, order="asc")
    assert len(page.data) == 1 and page.has_more
    next_page = client.responses.input_items.list(
        second.id, limit=100, order="asc", after=page.last_id
    )
    assert next_page.data
    other_key = (
        "fixture-other" if args.fixture else os.environ.get("ANY2API_E2E_OTHER_API_KEY")
    )
    if other_key:
        other = OpenAI(
            api_key=other_key,
            base_url=args.base_url,
            timeout=args.timeout,
            max_retries=0,
        )
        try:
            other.responses.retrieve(first.id)
            raise AssertionError("cross-key access succeeded")
        except openai.NotFoundError:
            pass
    client.responses.delete(first.id)
    try:
        client.responses.retrieve(first.id)
        raise AssertionError("deleted response remained visible")
    except openai.NotFoundError:
        pass
    passed("stored function loop/retrieve/pagination/delete/ownership")

    if not args.core:
        namespace = {
            "type": "namespace",
            "name": "local",
            "tools": [{**function, "name": "inspect"}],
        }
        with client.responses.stream(
            model=args.model,
            input="Inspect using the local namespace.",
            tools=[namespace],
            tool_choice={"type": "function", "namespace": "local", "name": "inspect"},
            store=False,
        ) as stream:
            for _ in stream:
                pass
            namespaced = stream.get_final_response()
        call = next(item for item in namespaced.output if item.type == "function_call")
        assert call.name == "inspect" and call.namespace == "local"
        passed("namespace identity/function argument stream")

        custom = client.responses.create(
            model=args.model,
            input="Call echo.",
            store=False,
            tools=[
                {
                    "type": "custom",
                    "name": "echo",
                    "description": "Echo a string",
                    "format": {"type": "text"},
                }
            ],
            tool_choice={"type": "custom", "name": "echo"},
        )
        custom_call = next(
            item for item in custom.output if item.type == "custom_tool_call"
        )
        assert custom_call.name == "echo" and custom_call.input
        replay = [item.model_dump(exclude_none=True) for item in custom.output]
        follow = client.responses.create(
            model=args.model,
            store=False,
            input=replay
            + [
                {
                    "type": "custom_tool_call_output",
                    "call_id": custom_call.call_id,
                    "output": "demo.txt",
                }
            ],
        )
        assert follow.output_text
        passed("custom tool/result replay")

        with client.responses.stream(
            model=args.model,
            input="Call echo.",
            store=False,
            tools=[{"type": "custom", "name": "echo", "format": {"type": "text"}}],
            tool_choice={"type": "custom", "name": "echo"},
        ) as stream:
            custom_deltas = []
            for event in stream:
                if event.type == "response.custom_tool_call_input.delta":
                    custom_deltas.append(event.delta)
            streamed_custom = stream.get_final_response()
        custom_call = next(
            item for item in streamed_custom.output if item.type == "custom_tool_call"
        )
        assert custom_deltas and "".join(custom_deltas) == custom_call.input
        passed("custom SDK stream accumulation")

    chat_tools = [
        {
            "type": "function",
            "function": {
                key: value for key, value in function.items() if key != "type"
            },
        }
    ]
    chat = client.chat.completions.create(
        model=args.model,
        messages=[
            {
                "role": "user",
                "content": "Use the inspection tool, then report the exact filename from its result.",
            }
        ],
        tools=chat_tools,
        tool_choice="required",
    )
    message = chat.choices[0].message
    assert chat.choices[0].finish_reason == "tool_calls" and message.tool_calls
    reply = client.chat.completions.create(
        model=args.model,
        messages=[
            {
                "role": "user",
                "content": "Use the inspection tool, then report the exact filename from its result.",
            },
            message.model_dump(exclude_none=True),
        ]
        + [
            {"role": "tool", "tool_call_id": call.id, "content": "demo.txt"}
            for call in message.tool_calls
        ],
    )
    assert reply.choices[0].message.content
    if args.core:
        assert "demo.txt" in reply.choices[0].message.content
    chunks = list(
        client.chat.completions.create(
            model=args.model,
            messages=[{"role": "user", "content": "Hello"}],
            stream=True,
            stream_options={"include_usage": True},
        )
    )
    assert chunks[-1].usage and any(
        chunk.choices and chunk.choices[0].delta.content for chunk in chunks
    )
    passed("chat function loop/stream/usage")

    if args.core or args.common:
        with client.responses.stream(
            model=args.model,
            input="Call inspect_workspace.",
            tools=[function],
            tool_choice={"type": "function", "name": "inspect_workspace"},
            parallel_tool_calls=False,
            store=False,
        ) as stream:
            deltas = []
            for event in stream:
                if event.type == "response.function_call_arguments.delta":
                    deltas.append(event.delta)
            response = stream.get_final_response()
        call = next(item for item in response.output if item.type == "function_call")
        assert call.name == "inspect_workspace" and "".join(deltas) == call.arguments
        assert isinstance(json.loads(call.arguments), dict)
        passed("ordinary function SSE/named choice/argument accumulation")

    if args.fixture:
        with client.responses.stream(
            model=args.model,
            input="fixture:parallel",
            store=False,
            tools=[function, {**function, "name": "inspect_second"}],
            parallel_tool_calls=True,
        ) as stream:
            for _ in stream:
                pass
            parallel = stream.get_final_response()
        calls = [item for item in parallel.output if item.type == "function_call"]
        assert len(calls) == 2 and len({call.call_id for call in calls}) == 2
        follow = client.responses.create(
            model=args.model,
            store=False,
            input=[item.model_dump(exclude_none=True) for item in parallel.output]
            + [
                {
                    "type": "function_call_output",
                    "call_id": call.call_id,
                    "output": "demo.txt",
                }
                for call in calls
            ],
        )
        assert follow.output_text
        passed("parallel tool streams/multiple results")
        try:
            client.responses.create(
                model=args.model,
                input="Hello",
                stream=True,
                store=False,
                tools=[{**function, "strict": True}],
            )
            raise AssertionError("emulated strict tools were accepted")
        except openai.BadRequestError:
            pass
        history = [{"role": "developer", "content": "Keep this rule."}] + [
            {
                "role": "user" if index % 2 == 0 else "assistant",
                "content": f"Turn {index}",
            }
            for index in range(45)
        ]
        assert (
            client.responses.create(model=args.model, input=history, store=False).status
            == "completed"
        )
        incomplete = client.responses.create(
            model=args.model, input="fixture:length", store=False
        )
        assert (
            incomplete.status == "incomplete"
            and incomplete.incomplete_details.reason == "max_output_tokens"
        )
        try:
            client.responses.create(
                model=args.model, input="fixture:rate-limit", stream=True, store=False
            )
            raise AssertionError("rate limit was hidden as HTTP 200")
        except openai.RateLimitError:
            pass
        try:
            client.responses.create(
                model="mimo/unknown", input="Hello", stream=True, store=False
            )
            raise AssertionError("unknown model was accepted")
        except openai.BadRequestError:
            pass
        passed("long history/incomplete/prestream errors")

        for protocol in ("chat", "responses"):
            for stream in (False, True):
                for marker, expected_status in (("fixture:upstream-error", 502), ("fixture:gateway-timeout", 504)):
                    try:
                        if protocol == "responses":
                            client.responses.create(model=args.model, input=marker, store=False, stream=stream)
                        else:
                            client.chat.completions.create(model=args.model,
                                messages=[{"role": "user", "content": marker}], stream=stream)
                        raise AssertionError(f"{protocol} error was accepted as HTTP 200")
                    except openai.APIStatusError as error:
                        assert error.status_code == expected_status
                        assert error.response.headers["content-type"].startswith("application/json")
                        assert error.response.json()["error"]["request_id"]
        passed("Chat/Responses 502/504 JSON and prestream status fidelity")

        with client.responses.stream(model=args.model, input="fixture:delayed-error", store=False) as stream:
            failed = next(event for event in stream if event.type == "response.failed")
            delayed = failed.response
        assert delayed.status == "failed" and delayed.error
        passed("delayed SSE failure/SDK terminal state")

    report = {
        "sdk_version": openai.__version__,
        "client_timeout_seconds": args.timeout,
        "upstream": "fixture" if args.fixture else "authorized-provider",
        "model": args.model,
        "core_only": args.core,
        "common_only": args.common,
        "reasoning_requested": not (args.no_reasoning or args.core or args.common),
        "cross_key_checked": bool(other_key),
        "checks": checks,
        "first_delta_seconds": first_delta,
        "stream_seconds": elapsed,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
