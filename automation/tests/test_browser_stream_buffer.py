import shutil
import subprocess

import pytest

from any2api_automation.providers.arena_browser import _arena_ndjson_stream_script
from any2api_automation.providers.browser_stream_buffer import BrowserStreamBuffer
from any2api_automation.providers.mimo_browser import _stream_request, default_runtime_plan
from any2api_automation.providers.page_fetch_browser import _stream_request_script


@pytest.mark.asyncio
async def test_overflow_preserves_partial_output_and_allows_explicit_failure():
    buffer = BrowserStreamBuffer(maximum_bytes=80)
    first = {"type": "data", "data": "a" * 25}
    buffer.put_nowait(first)
    with pytest.raises(BufferError, match="buffer limit"):
        buffer.put_nowait({"type": "data", "data": "b" * 25})
    await buffer.put({"type": "status", "status": 502})
    await buffer.put({"type": "error", "data": "buffer limit exceeded"})
    await buffer.put({"type": "done"})
    assert await buffer.get() == first
    assert [(await buffer.get())["type"] for _ in range(3)] == ["status", "error", "done"]
    assert buffer.empty()
    assert buffer._buffered_bytes == 0


@pytest.mark.asyncio
async def test_draining_releases_capacity_and_unicode_is_counted_as_bytes():
    buffer = BrowserStreamBuffer(maximum_bytes=80, maximum_events=1)
    frame = {"type": "data", "data": "你" * 10}
    buffer.put_nowait(frame)
    with pytest.raises(BufferError):
        buffer.put_nowait({"type": "data", "data": ""})
    assert await buffer.get() == frame
    buffer.put_nowait(frame)
    assert await buffer.get() == frame
    with pytest.raises(BufferError):
        buffer.put_nowait({"type": "data", "data": "你" * 40})


@pytest.mark.parametrize("provider", ["page_fetch", "mimo", "arena"])
def test_unframed_unicode_stream_fails_with_a_byte_limit_and_releases_page_work(provider):
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute browser stream scripts")
    scripts = {
        "page_fetch": _stream_request_script("__emit"),
        "mimo": _stream_request(default_runtime_plan().active.rules),
        "arena": _arena_ndjson_stream_script("__emit", "v3", "v2", 50),
    }
    script = (
        "const stream = "
        + scripts[provider]
        + ";\n"
        + r"""
const assert = require('node:assert/strict');
global.window = global;
global.__emit = global.__any2apiMimoEmit = async () => {};
let signal;
global.fetch = async (_, options) => {
  signal = options.signal;
  return {
    ok: true, status: 200, headers: new Headers(),
    body: new ReadableStream({start(controller) {
      controller.enqueue(new TextEncoder().encode('你'.repeat(40)));
      controller.close();
    }}),
  };
};
(async () => {
  await assert.rejects(stream({requestId: 'bounded', method: 'POST', url: 'https://example.test',
    body: '{}', headers: {}, maximumBytes: 80, timeoutMs: 1000,
    nativeSubmission: {cx: 'native', recaptchaV3Token: 'native-v3'}}), /buffered byte limit/);
  assert.equal(signal.aborted, true);
  for (const controllers of [window.__any2apiPageStreamControllers,
      window.__any2apiMimoStreamControllers, window.__any2apiArenaStreamControllers]) {
    if (controllers) assert.equal(controllers.size, 0);
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
"""
    )
    result = subprocess.run(
        [node, "-e", script], capture_output=True, text=True, timeout=10, check=False
    )
    assert result.returncode == 0, result.stderr
