"""Exercise installed browser binaries offline as the image's non-root user."""

import asyncio
import importlib.metadata
import json
import os
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path

from camoufox.async_api import AsyncCamoufox
from patchright.async_api import async_playwright

LOCAL_PAGE = '<html lang="zh"><meta charset="utf-8"><body>容器运行检查</body></html>'


@contextmanager
def virtual_display():
    display = ":98"
    socket = Path("/tmp/.X11-unix/X98")
    if socket.exists():
        raise RuntimeError("Browser environment check display is already occupied")
    process = subprocess.Popen(
        ["Xvfb", display, "-screen", "0", "1365x900x24", "-nolisten", "tcp", "-ac"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    previous = os.environ.get("DISPLAY")
    try:
        deadline = time.monotonic() + 10
        while not socket.exists():
            if process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("Browser environment check display could not start")
            time.sleep(0.1)
        os.environ["DISPLAY"] = display
        yield
    finally:
        if previous is None:
            os.environ.pop("DISPLAY", None)
        else:
            os.environ["DISPLAY"] = previous
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


async def check_page(browser, engine: str, headless: bool) -> dict[str, object]:
    context = await browser.new_context()
    try:
        await context.route("**/*", lambda route: route.abort())
        page = await context.new_page()
        await page.set_content(LOCAL_PAGE, timeout=15_000)
        result = await page.evaluate(
            """() => ({
              text: document.body.textContent,
              canvas: !!document.createElement('canvas').getContext('2d'),
              characterWidth: (() => {
                const ctx = document.createElement('canvas').getContext('2d');
                return ctx ? ctx.measureText('容器运行检查').width : 0;
              })()
            })"""
        )
        if result["text"] != "容器运行检查" or not result["canvas"]:
            raise RuntimeError(f"{engine} could not render the local check page")
        if result["characterWidth"] <= 0:
            raise RuntimeError(f"{engine} could not measure Chinese text")
        return {"engine": engine, "headless": headless, "version": browser.version}
    finally:
        async with asyncio.timeout(10):
            await context.close()


async def check_browsers() -> list[dict[str, object]]:
    results = []
    async with asyncio.timeout(150), async_playwright() as playwright:
        for headless in (True, False):
            browser = await playwright.chromium.launch(headless=headless, timeout=30_000)
            try:
                results.append(await check_page(browser, "chromium", headless))
            finally:
                async with asyncio.timeout(10):
                    await browser.close()
            async with AsyncCamoufox(
                headless=headless, geoip=False, humanize=False, timeout=30_000
            ) as camoufox:
                results.append(await check_page(camoufox, "camoufox", headless))
    return results


def main() -> None:
    if os.getuid() == 0:
        raise SystemExit("Browser environment check must run as the non-root application user")
    fonts = subprocess.run(
        ["fc-list", ":lang=zh", "family"], check=True, capture_output=True, text=True, timeout=10
    ).stdout.splitlines()
    if not fonts:
        raise SystemExit("Browser runtime is missing Chinese fonts")
    with virtual_display():
        browsers = asyncio.run(check_browsers())
    print(
        json.dumps(
            {
                "status": "PASS",
                "uid": os.getuid(),
                "packages": {
                    name: importlib.metadata.version(name)
                    for name in ("patchright", "playwright", "camoufox")
                },
                "chinese_font_families": len(fonts),
                "browsers": browsers,
                "vendor_generation_requests": 0,
            }
        )
    )


if __name__ == "__main__":
    main()
