"""Validate stored Responses input items against the official SDK resource schema."""

import argparse
import json
import os
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--key-env", default="ANY2API_E2E_API_KEY")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise SystemExit("Report already exists; use a fresh path")
    if args.sdk_path:
        sys.path.insert(0, str(args.sdk_path.resolve()))
    import openai
    from openai.types.responses import ResponseItem
    from pydantic import TypeAdapter

    key = os.environ.get(args.key_env)
    if not key:
        raise SystemExit(f"Missing API key environment variable: {args.key_env}")
    rows = []
    with openai.OpenAI(
        base_url=args.base_url, api_key=key, timeout=180, max_retries=0
    ) as client:
        for structured in [False, True]:
            response_id = None
            try:
                messages = []
                for role in ["system", "developer", "user", "assistant", "user"]:
                    message = {
                        "role": role,
                        "content": f"合成 SDK 资源验证：{role}",
                    }
                    if role == "assistant":
                        message["phase"] = "commentary"
                    if structured:
                        block = {
                            "type": "output_text"
                            if role == "assistant"
                            else "input_text",
                            "text": message["content"],
                        }
                        if role == "assistant":
                            block["annotations"] = []
                        message["content"] = [block]
                    messages.append(message)
                messages.extend(
                    [
                        {
                            "type": "function_call",
                            "call_id": "call_resource",
                            "name": "inspect_resource",
                            "arguments": "{}",
                        },
                        {
                            "type": "function_call_output",
                            "call_id": "call_resource",
                            "output": "合成核验完成",
                        },
                        {
                            "type": "custom_tool_call",
                            "call_id": "call_custom",
                            "name": "mark_review",
                            "input": "合成文档",
                        },
                        {
                            "type": "custom_tool_call_output",
                            "call_id": "call_custom",
                            "output": "合成标记完成",
                        },
                        {"role": "user", "content": "请确认工具核验结果。"},
                    ]
                )
                first = client.responses.create(
                    model=args.model, input=messages, store=True
                )
                response_id = first.id
                assert first.status == "completed"
                page = client.responses.input_items.list(
                    first.id, limit=100, order="asc"
                )
                items = [
                    item.model_dump(exclude_none=True, warnings="error")
                    for item in page.data
                ]
                for item in items:
                    TypeAdapter(ResponseItem).validate_python(item)
                assert len(items) == len(messages)
                for source, resource in zip(messages, items, strict=True):
                    assert resource["status"] == "completed"
                    if source.get("type") in {
                        "function_call",
                        "function_call_output",
                        "custom_tool_call",
                        "custom_tool_call_output",
                    }:
                        for field, value in source.items():
                            assert resource[field] == value
                        continue
                    assert resource["role"] == source["role"]
                    assert isinstance(resource["content"], list)
                    expected_type = (
                        "output_text" if source["role"] == "assistant" else "input_text"
                    )
                    assert resource["content"][0]["type"] == expected_type
                    if source["role"] == "assistant":
                        assert resource["phase"] == "commentary"
                        assert resource["content"][0]["annotations"] == []
                short = client.responses.input_items.list(
                    first.id, limit=2, order="asc"
                )
                after = client.responses.input_items.list(
                    first.id, limit=100, order="asc", after=short.last_id
                )
                assert short.has_more
                assert [item.id for item in [*short.data, *after.data]] == [
                    item["id"] for item in items
                ]
                reverse = client.responses.input_items.list(
                    first.id, limit=100, order="desc"
                )
                assert [item.id for item in reverse.data] == list(
                    reversed([item["id"] for item in items])
                )
                replay = client.responses.create(
                    model=args.model, input=items, store=False
                )
                assert replay.status == "completed" and replay.output_text
                rows.append(
                    {
                        "check": "structured messages"
                        if structured
                        else "easy messages",
                        "passed": True,
                        "request_id": first._request_id,
                        "replay_request_id": replay._request_id,
                        "resource_schema": True,
                        "pagination": True,
                        "resource_replay": True,
                    }
                )
            finally:
                if response_id:
                    client.responses.delete(response_id)
                    try:
                        client.responses.retrieve(response_id)
                        raise AssertionError("Deleted synthetic state remains visible")
                    except openai.NotFoundError:
                        pass
    report = {"sdk_version": openai.__version__, "model": args.model, "checks": rows}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
