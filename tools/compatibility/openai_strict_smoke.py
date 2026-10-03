"""Verify model retrieval and strict caller-owned functions through the official SDK."""

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
    parser.add_argument("--provider", required=True)
    parser.add_argument("--key-manifest", type=Path)
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.sdk_path:
        sys.path.insert(0, str(args.sdk_path.resolve()))
    import openai
    from pydantic import BaseModel

    class Addends(BaseModel):
        a: int
        b: int

    api_key = os.environ.get("ANY2API_E2E_API_KEY", "")
    if args.key_manifest:
        manifest = json.loads(args.key_manifest.read_text(encoding="utf-8-sig"))
        api_key = manifest["keys"][args.provider]["api_key"]
    if not api_key:
        raise SystemExit("API key is required via environment or protected key manifest")
    results = {"sdk_version": openai.__version__, "base_url": args.base_url,
               "provider": args.provider, "model": args.model, "checks": []}

    def check(name, operation):
        started = time.monotonic()
        try:
            detail = operation() or {}
            row = {"check": name, "passed": True, **detail}
        except openai.APIStatusError as error:
            body = error.body if isinstance(error.body, dict) else {}
            detail = body.get("error", body)
            row = {"check": name, "passed": False, "http_status": error.status_code,
                   "error_code": detail.get("code") if isinstance(detail, dict) else None,
                   "request_id": error.request_id}
        except Exception as error:
            row = {"check": name, "passed": False, "exception_type": type(error).__name__}
        row["seconds"] = round(time.monotonic() - started, 3)
        results["checks"].append(row)
        print(json.dumps(row), flush=True)

    function = openai.pydantic_function_tool(Addends, name="calculate_sum")
    response_function = {"type": "function", **function["function"]}
    prompt = "Call calculate_sum with a=12 and b=30. Do not calculate the answer yourself."
    if args.fixture:
        prompt += " fixture:strict-valid"
    with openai.OpenAI(base_url=args.base_url, api_key=api_key,
                       timeout=args.timeout, max_retries=0) as client:
        def model_contract():
            model = client.models.retrieve(args.model)
            assert model.id == args.model and model.owned_by == args.provider
            assert model.capabilities["tools"]["strict"] is True
            assert model.capabilities["tools"]["strict_validation"] == "gateway_schema_validation"
            return {"strict_validation": "gateway_schema_validation"}

        check("models.retrieve/capabilities", model_contract)

        def missing_model():
            try:
                client.models.retrieve(f"{args.provider}/missing-strict-smoke-model")
                raise AssertionError("missing model was returned")
            except openai.NotFoundError as error:
                detail = error.response.json()["error"]
                assert detail["code"] == "model_not_found" and detail["request_id"]
            return {"http_status": 404, "error_code": "model_not_found"}

        check("missing model/OpenAI JSON", missing_model)

        def invalid_schema():
            invalid = {**response_function, "parameters": {
                "type": "object", "properties": {}, "additionalProperties": True,
            }}
            try:
                client.responses.create(model=args.model, input="Hello", tools=[invalid], store=False)
                raise AssertionError("invalid strict schema was accepted")
            except openai.BadRequestError as error:
                detail = error.response.json()["error"]
                assert detail["param"] == "tools.parameters" and detail["request_id"]
            return {"http_status": 400}

        check("invalid strict schema/preflight", invalid_schema)

        def chat_roundtrip():
            response = client.chat.completions.parse(
                model=args.model, messages=[{"role": "user", "content": prompt}],
                tools=[function], tool_choice="required", parallel_tool_calls=False,
            )
            message = response.choices[0].message
            assert response.choices[0].finish_reason == "tool_calls"
            assert len(message.tool_calls or []) == 1
            call = message.tool_calls[0]
            assert call.function.name == "calculate_sum"
            assert isinstance(call.function.parsed_arguments, Addends)
            assert call.function.parsed_arguments == Addends(a=12, b=30)
            follow = client.chat.completions.create(
                model=args.model,
                messages=[{"role": "user", "content": prompt},
                          {"role": "assistant", "content": message.content, "tool_calls": [{
                              "id": call.id, "type": "function", "function": {
                                  "name": call.function.name, "arguments": call.function.arguments,
                              },
                          }]},
                          {"role": "tool", "tool_call_id": call.id, "content": "42"}],
                tools=[function], tool_choice="none",
            )
            assert "42" in (follow.choices[0].message.content or "")
            return {"calls": 1, "pydantic_parsed": True, "result_replayed": True}

        check("Chat strict/Pydantic/function result", chat_roundtrip)

        def responses_roundtrip():
            with client.responses.stream(model=args.model, input=prompt, tools=[response_function],
                                         tool_choice="required", parallel_tool_calls=False, store=False) as stream:
                event_types = set()
                for event in stream:
                    event_types.add(event.type)
                response = stream.get_final_response()
            assert response.status == "completed"
            calls = [item for item in response.output if item.type == "function_call"]
            assert len(calls) == 1 and calls[0].name == "calculate_sum"
            assert json.loads(calls[0].arguments) == {"a": 12, "b": 30}
            assert "response.function_call_arguments.done" in event_types
            follow = client.responses.create(
                model=args.model,
                input=[{"role": "user", "content": prompt},
                       *[item.model_dump(exclude_none=True) for item in response.output],
                       {"type": "function_call_output", "call_id": calls[0].call_id, "output": "42"}],
                tools=[response_function], tool_choice="none", store=False,
            )
            assert follow.status == "completed" and "42" in follow.output_text
            return {"calls": 1, "SSE_arguments_done": True, "result_replayed": True}

        check("Responses strict/SSE/function result", responses_roundtrip)

        if args.fixture:
            def invalid_upstream():
                with client.responses.stream(model=args.model, input="fixture:strict-invalid",
                                             tools=[response_function], tool_choice="required", store=False) as stream:
                    events = list(stream)
                    response = next(event.response for event in events if event.type == "response.failed")
                assert response.status == "failed" and response.error.code == "tool_call_generation_failed"
                assert not any(event.type.startswith("response.function_call_arguments") for event in events)
                assert not any(item.type == "function_call" for item in response.output)
                return {"error_code": response.error.code, "invalid_arguments_exposed": False}

            check("invalid upstream strict arguments/SSE failed", invalid_upstream)

    results["passed"] = all(row["passed"] for row in results["checks"])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": results["passed"], "checks": len(results["checks"])}), flush=True)
    raise SystemExit(0 if results["passed"] else 1)


if __name__ == "__main__":
    main()
