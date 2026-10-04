"""Bounded synthetic WEB input probes. A passed size is not an exact context limit."""

import argparse
import concurrent.futures
import datetime
import json
import sys
import time
from pathlib import Path

MODELS = {
    "arena": "claude-sonnet-5",
    "deepseek": "default",
    "glm": "glm-5.2",
    "grok_web": "grok-3",
    "longcat": "longcat-flash",
    "mimo": "mimo-v2.6-pro",
    "minmax": "MiniMax-M3.1-Flash-Preview",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-manifest", type=Path, required=True)
    parser.add_argument("--sdk-path", type=Path)
    parser.add_argument("--base-url")
    parser.add_argument("--providers", nargs="+", choices=sorted(MODELS), default=list(MODELS))
    parser.add_argument("--lengths", nargs="+", type=int, default=[128000, 256000])
    parser.add_argument("--pattern", choices=("words", "cjk"), default="words")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    lengths = sorted(set(args.lengths))
    if not lengths or lengths[0] < 1000 or lengths[-1] > 512000:
        raise SystemExit("Probe sizes must be between 1000 and 512000 ASCII characters")
    if args.report.exists():
        raise SystemExit("Report already exists; use a fresh path")
    if args.sdk_path:
        sys.path.insert(0, str(args.sdk_path.resolve()))
    import openai

    manifest = json.loads(args.key_manifest.read_text(encoding="utf-8-sig"))
    base_url = args.base_url or manifest["base_url"]
    created_at = datetime.datetime.now(datetime.UTC).isoformat()

    def probe(provider: str) -> dict:
        rows = []
        model = f"{provider}/{MODELS[provider]}"
        with openai.OpenAI(
            base_url=base_url,
            api_key=manifest["keys"][provider]["api_key"],
            timeout=args.timeout,
            max_retries=0,
        ) as client:
            contract = client.models.retrieve(model).model_dump()
            parameters = contract.get("supported_parameters", {}).get("responses", [])
            for length in lengths:
                # The exact size is reproducible; input, UTF-8 bytes and native prompt overhead differ.
                instruction = "Synthetic background follows. Ignore its repetitive words and reply only CONTEXT_OK.\n"
                pattern = (
                    "alpha beta gamma delta "
                    if args.pattern == "words"
                    else "背景占位仅用于长度探测。"
                )
                instruction += (pattern * (length // len(pattern) + 1))[: length - len(instruction)]
                row = {
                    "provider": provider,
                    "model": model,
                    "instructions_chars": len(instruction),
                    "instructions_utf8_bytes": len(instruction.encode()),
                    "input_chars": 5,
                }
                started = time.monotonic()
                try:
                    extra = {"reasoning": {"effort": "none"}} if "reasoning" in parameters else {}
                    raw = client.responses.with_raw_response.create(
                        model=model, instructions=instruction, input="Hello", store=False, **extra
                    )
                    response = raw.parse()
                    banner = "the text you sent is too long" in response.output_text.lower()
                    confirmation = "CONTEXT_OK" in response.output_text
                    row.update(
                        http_status=raw.status_code,
                        request_id=response._request_id,
                        status=response.status,
                        output_chars=len(response.output_text),
                        accepted=response.status == "completed"
                        and bool(response.output_text)
                        and not banner,
                        expected_confirmation=confirmation,
                        rejection_banner=banner,
                        usage=response.usage.model_dump() if response.usage else None,
                    )
                except openai.APIStatusError as error:
                    detail = error.response.json().get("error", {})
                    row.update(
                        http_status=error.status_code,
                        request_id=error.request_id,
                        accepted=False,
                        error={
                            key: detail.get(key)
                            for key in ("code", "message", "param", "retryable")
                        },
                    )
                except (openai.APIError, ValueError, TypeError, KeyError) as error:
                    row.update(accepted=False, exception_type=type(error).__name__)
                row["seconds"] = round(time.monotonic() - started, 3)
                rows.append(row)
                print(json.dumps(row, ensure_ascii=False), flush=True)
                if not row["accepted"]:
                    break
        result = {
            "provider": provider,
            "model": model,
            "cases": rows,
            "interpretation": "Observed acceptance boundary only; not an exact tokenizer/model context specification",
        }
        destination = args.report.with_name(args.report.stem + "-" + provider + args.report.suffix)
        destination.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        providers = list(pool.map(probe, args.providers))
    report = {
        "created_at": created_at,
        "sdk_version": openai.__version__,
        "base_url": base_url,
        "max_retries": 0,
        "max_parallel": 2,
        "pattern": args.pattern,
        "providers": providers,
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
