"""Check declared WEB mappings, optional nulls and OpenAI input-limit errors with existing keys."""

import argparse
import concurrent.futures
import datetime
import json
import sys
import time
from pathlib import Path

from web_context_probe import MODELS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-manifest", type=Path, required=True)
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--base-url")
    parser.add_argument("--providers", nargs="+", choices=sorted(MODELS), default=list(MODELS))
    parser.add_argument("--timeout", type=float, default=180)
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
    native_controls = {
        "arena": {"web_search": False},
        "deepseek": {"reasoning": {"effort": "none"}, "web_search": False},
        "glm": {
            "temperature": 0.4,
            "top_p": 0.7,
            "max_output_tokens": 128,
            "reasoning": {"effort": "none"},
            "web_search": False,
        },
        "longcat": {"reasoning": {"effort": "none"}, "search_enabled": False},
        "mimo": {
            "temperature": 0.4,
            "top_p": 0.7,
            "max_output_tokens": 128000,
            "reasoning": {"effort": "none"},
            "web_search_status": "disabled",
        },
        "minmax": {"reasoning": {"effort": "none"}},
    }

    def verify(provider: str) -> dict:
        rows = []
        model = f"{provider}/{MODELS[provider]}"
        key = manifest["keys"][provider]["api_key"]

        def check(name, operation):
            started = time.monotonic()
            try:
                row = {"check": name, "passed": True, **(operation() or {})}
            except openai.APIStatusError as error:
                body = error.body if isinstance(error.body, dict) else {}
                detail = body.get("error", body)
                row = {
                    "check": name,
                    "passed": False,
                    "http_status": error.status_code,
                    "error_code": detail.get("code"),
                    "request_id": error.request_id,
                }
            except (
                openai.APIError,
                httpx.HTTPError,
                AssertionError,
                ValueError,
                TypeError,
                KeyError,
            ) as error:
                row = {"check": name, "passed": False, "exception_type": type(error).__name__}
            row.update(provider=provider, seconds=round(time.monotonic() - started, 3))
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)

        with openai.OpenAI(
            base_url=base_url, api_key=key, timeout=args.timeout, max_retries=0
        ) as client:

            def contract():
                detail = client.models.retrieve(model).model_dump()
                mapping = detail["parameter_adaptation"]
                assert mapping == detail["capabilities"]["parameter_adaptation"]
                assert mapping["function_tools"]["executor"] == "client"
                assert mapping["input"]["token_limit_verified"] is False
                if provider == "mimo":
                    assert mapping["top_p"]["target"] == "modelConfig.topP"
                    assert mapping["max_output_tokens"]["mode"] == "non_binding_only"
                    assert mapping["max_output_tokens"]["enforced"] is False
                if provider == "glm":
                    assert mapping["max_output_tokens"]["target"] == "params.max_tokens"
                if provider == "longcat":
                    assert mapping["reasoning"]["target"] == "reasonEnabled"
                    assert mapping["search"]["target"] == "searchEnabled"
                if provider in {"arena", "grok_web"}:
                    assert mapping["max_output_tokens"]["mode"] == "unsupported"
                return {"mapping": mapping}

            check("models.retrieve/parameter_adaptation", contract)

            def request(protocol, controls, *, expected=None, long_input=False, cap=False):
                prompt = "Please reply only PARAM_OK."
                if cap:
                    prompt = "Write 100 numbered lines, each with one English sentence. Start immediately."
                instructions = "alpha beta gamma delta " * 7000 if long_input else None
                try:
                    if protocol == "chat":
                        messages = [{"role": "user", "content": prompt}]
                        if instructions:
                            messages.insert(0, {"role": "system", "content": instructions})
                        raw = client.chat.completions.with_raw_response.create(
                            model=model, messages=messages, store=False, extra_body=controls
                        )
                        response = raw.parse()
                        assert response.choices[0].message.content
                        usage = response.usage.model_dump() if response.usage else None
                        tokens = response.usage.completion_tokens if response.usage else None
                    else:
                        raw = client.responses.with_raw_response.create(
                            model=model,
                            input=prompt,
                            instructions=instructions,
                            store=False,
                            extra_body=controls,
                        )
                        response = raw.parse()
                        assert response.status == "completed" and response.output_text
                        usage = response.usage.model_dump() if response.usage else None
                        tokens = response.usage.output_tokens if response.usage else None
                    assert expected is None
                    if cap:
                        assert tokens is not None and 0 < tokens <= 16
                        assert usage["usage_source"] == "UPSTREAM"
                    return {
                        "http_status": raw.status_code,
                        "request_id": response._request_id,
                        "usage": usage,
                    }
                except openai.APIStatusError as error:
                    if expected is None:
                        raise
                    detail = error.response.json()["error"]
                    assert error.status_code == 400 and detail["code"] == expected
                    assert detail.get("request_id") and detail.get("retryable") is False
                    if expected == "context_length_exceeded":
                        assert detail["type"] == "invalid_request_error"
                        assert detail["param"] == ("messages" if protocol == "chat" else "input")
                    return {
                        "http_status": error.status_code,
                        "error_code": detail["code"],
                        "request_id": error.request_id,
                        "retryable": detail.get("retryable"),
                    }

            check(
                "chat/null-controls",
                lambda: request(
                    "chat",
                    {
                        "temperature": None,
                        "top_p": None,
                        "max_tokens": None,
                        "reasoning_effort": None,
                    },
                ),
            )
            check(
                "responses/null-controls",
                lambda: request(
                    "responses",
                    {
                        "temperature": None,
                        "top_p": None,
                        "max_output_tokens": None,
                        "reasoning": None,
                        "stream_options": None,
                    },
                ),
            )
            if provider in native_controls:
                check(
                    "responses/mapped-controls",
                    lambda: request("responses", native_controls[provider]),
                )
            if provider != "glm":
                value = 128 if provider == "mimo" else 128000
                check(
                    "responses/unsupported-output-bound",
                    lambda: request(
                        "responses", {"max_output_tokens": value}, expected="unsupported_parameter"
                    ),
                )
            if provider == "glm":
                check(
                    "responses/invalid-output-bound",
                    lambda: request(
                        "responses", {"max_output_tokens": 0}, expected="invalid_request_error"
                    ),
                )
                for protocol, field in (
                    ("chat", "max_tokens"),
                    ("chat", "max_completion_tokens"),
                    ("responses", "max_output_tokens"),
                ):
                    controls = (
                        {field: 16, "reasoning_effort": "none"}
                        if protocol == "chat"
                        else {field: 16, "reasoning": {"effort": "none"}}
                    )
                    check(
                        protocol + "/native-bound/" + field,
                        lambda protocol=protocol, controls=controls: request(
                            protocol, controls, cap=True
                        ),
                    )
            if provider == "mimo":
                for protocol in ("chat", "responses"):
                    check(
                        protocol + "/context-length-error",
                        lambda protocol=protocol: request(
                            protocol, {}, expected="context_length_exceeded", long_input=True
                        ),
                    )

                    def stream_error(protocol=protocol):
                        payload = {"model": model, "stream": True, "store": False}
                        background = "alpha beta gamma delta " * 7000
                        if protocol == "chat":
                            payload["messages"] = [
                                {"role": "system", "content": background},
                                {"role": "user", "content": "Please reply only PARAM_OK."},
                            ]
                        else:
                            payload.update(
                                instructions=background, input="Please reply only PARAM_OK."
                            )
                        endpoint = "/chat/completions" if protocol == "chat" else "/responses"
                        failures = completed = done = 0
                        has_code = False
                        with (
                            httpx.Client(timeout=args.timeout) as transport,
                            transport.stream(
                                "POST",
                                base_url.rstrip("/") + endpoint,
                                headers={"Authorization": "Bearer " + key},
                                json=payload,
                            ) as response,
                        ):
                            request_id = response.headers.get("x-request-id")
                            if response.status_code == 400:
                                assert "application/json" in response.headers.get(
                                    "content-type", ""
                                )
                                detail = json.loads(response.read())["error"]
                                assert detail["code"] == "context_length_exceeded"
                                assert detail["type"] == "invalid_request_error"
                                assert detail["param"] == (
                                    "messages" if protocol == "chat" else "input"
                                )
                                assert (
                                    detail["retryable"] is False
                                    and detail["request_id"] == request_id
                                )
                                return {
                                    "http_status": 400,
                                    "request_id": request_id,
                                    "error_code": "context_length_exceeded",
                                    "error_delivery": "json_before_stream",
                                }
                            assert response.status_code == 200
                            for line in response.iter_lines():
                                failures += line == "event: response.failed"
                                completed += line == "event: response.completed"
                                done += line == "data: [DONE]"
                                has_code = has_code or "context_length_exceeded" in line
                        assert has_code and completed == 0
                        assert failures == 1 if protocol == "responses" else done == 1
                        return {
                            "http_status": 200,
                            "request_id": request_id,
                            "failed_terminals": failures,
                            "done_markers": done,
                            "error_code": "context_length_exceeded",
                            "error_delivery": "sse_after_stream_start",
                        }

                    check(protocol + "/context-length-error-sse", stream_error)
        result = {"provider": provider, "model": model, "checks": rows}
        destination = args.report.with_name(args.report.stem + "-" + provider + args.report.suffix)
        destination.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        providers = list(pool.map(verify, args.providers))
    report = {
        "created_at": started_at,
        "base_url": base_url,
        "sdk_version": openai.__version__,
        "max_retries": 0,
        "max_parallel": 2,
        "providers": providers,
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if not all(row["passed"] for provider in providers for row in provider["checks"]):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
