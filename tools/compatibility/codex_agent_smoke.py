"""Run the real Codex CLI and a readonly command in a temporary test workspace."""

import argparse
import json
import os
import shutil
import struct
import subprocess
import tempfile
import urllib.request
import zlib
from pathlib import Path


def fixture_png() -> bytes:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
        )

    pixels = (b"\x00" + b"\xf0\x80\x40" * 32) * 32
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(pixels))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--sandbox", choices=["read-only", "workspace-write"], default="read-only")
    parser.add_argument("--tool", choices=["command", "image"], default="command")
    args = parser.parse_args()
    if args.tool == "image" and not args.fixture:
        parser.error(
            "--tool image is reserved for the controlled fixture; use --tool command for a real provider."
        )
    if not os.environ.get("ANY2API_E2E_API_KEY"):
        raise SystemExit("Set ANY2API_E2E_API_KEY to an authorized test key.")
    executable = shutil.which(args.codex)
    if not executable:
        raise SystemExit("Codex CLI was not found.")
    version = subprocess.run(
        [executable, "--version"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout.strip()
    fixture_requests = []
    control = args.base_url.removesuffix("/v1") + "/__fixture/requests"
    if args.fixture:
        with urllib.request.urlopen(control, timeout=10) as response:
            fixture_requests = json.load(response)
    with tempfile.TemporaryDirectory(prefix="any2api-codex-smoke-") as directory:
        Path(directory, "demo.txt").write_text(
            "Synthetic gateway interoperability fixture.\n", encoding="utf-8"
        )
        prompt = "List the files in this workspace using a local readonly command, then summarize the result."
        if args.tool == "image":
            image = Path(directory, "demo.png")
            image.write_bytes(fixture_png())
            prompt = f"Open the local image with view_image, then confirm it was read.\nfixture_image_path={image}"
        command = [
            executable,
            "exec",
            "--ignore-user-config",
            "--ephemeral",
            "--skip-git-repo-check",
            "--disable",
            "apps",
            "--disable",
            "plugins",
            "--disable",
            "multi_agent",
            "-s",
            args.sandbox,
            "-C",
            directory,
            "--json",
            "-c",
            'model_provider="any2api"',
            "-c",
            f"model={json.dumps(args.model)}",
            "-c",
            'model_providers.any2api.name="Any2API"',
            "-c",
            f"model_providers.any2api.base_url={json.dumps(args.base_url)}",
            "-c",
            'model_providers.any2api.env_key="ANY2API_E2E_API_KEY"',
            "-c",
            'model_providers.any2api.wire_api="responses"',
            "-c",
            "model_providers.any2api.supports_websockets=false",
            "-c",
            'web_search="disabled"',
            prompt,
        ]
        result = subprocess.run(
            command,
            check=False,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
    events = []
    for line in result.stdout.splitlines():
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    completed = [event["item"] for event in events if event.get("type") == "item.completed"]
    commands = [item for item in completed if item.get("type") == "command_execution"]
    messages = [item.get("text", "") for item in completed if item.get("type") == "agent_message"]
    assert result.returncode == 0, (
        result.returncode,
        result.stderr[-2000:],
        result.stdout[-3000:],
    )
    if args.tool == "command":
        assert commands and all(item.get("exit_code") == 0 for item in commands), completed
        assert any("demo.txt" in item.get("aggregated_output", "") for item in commands), commands
        assert messages and "demo.txt" in messages[-1], messages
    else:
        assert messages and "LOCAL_IMAGE_READ_OK" in messages[-1], (
            messages,
            result.stderr[-1000:],
        )
    report = {
        "codex_version": version,
        "upstream": "fixture" if args.fixture else "authorized-provider",
        "model": args.model,
        "sandbox": args.sandbox,
        "exit_code": result.returncode,
        "completed_commands": len(commands),
        "tool": args.tool,
        "tool_output_verified": True,
        "final_answer_verified": True,
        "model_metadata_warning": "Model metadata" in result.stderr
        or any("Model metadata" in item.get("message", "") for item in completed),
    }
    if args.fixture:
        with urllib.request.urlopen(control, timeout=10) as response:
            requests = [
                request
                for request in json.load(response)[len(fixture_requests) :]
                if request["model"] == args.model.removeprefix("mimo/")
            ]
        assert len(requests) >= 2 and any(request["tool_results"] > 0 for request in requests), (
            requests
        )
        if args.tool == "image":
            assert any(request["image_inputs"] > 0 for request in requests), requests
            report["image_input_requests"] = sum(
                request["image_inputs"] > 0 for request in requests
            )
        report["tool_result_requests"] = sum(request["tool_results"] > 0 for request in requests)
        report["maximum_retained_messages"] = max(
            request["retained_messages"] for request in requests
        )
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"PASS Codex Responses -> local {args.tool} -> function_call_output -> final answer",
        flush=True,
    )
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
