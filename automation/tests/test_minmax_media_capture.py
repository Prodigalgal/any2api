import json
import shutil
import subprocess
from unittest.mock import AsyncMock

import pytest

from any2api_automation.providers import minmax
from any2api_automation.providers.minmax_browser import (
    _INSTALL_MESSAGE_HOOK,
    _READ_CAPTURED_MESSAGES,
    _RESTORE_MESSAGE_HOOK,
    MinmaxOfficialBrowserTransport,
    _Session,
)


def test_capture_blocks_generation_and_restores_fetch_xhr_without_stale_payloads() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the browser capture hook")
    script = (
        """
const assert = require('node:assert/strict');
global.window = global;
global.location = {origin: 'https://agent.minimax.io'};
let sent = 0;
window.fetch = async () => {sent++; return {status: 200};};
class Xhr {
  open(method, url) {this.url = url;}
  send(body) {sent++;}
  abort() {this.aborted = true;}
}
window.XMLHttpRequest = Xhr;
const originalFetch = window.fetch;
const originalSend = Xhr.prototype.send;
"""
        + "\nconst install = "
        + _INSTALL_MESSAGE_HOOK
        + ";\nconst read = "
        + _READ_CAPTURED_MESSAGES
        + ";\n"
        + """
(async () => {
  install();
  const url = 'https://agent-stream.minimax.io/archon/api/v1/session/1/message';
  await assert.rejects(fetch(url, {method: 'POST', body: '{"content":"first","attachments":[{}]}'}), {name: 'AbortError'});
  const xhr = new Xhr();
  xhr.open('POST', url);
  xhr.send('{"content":"second","attachments":[{}]}');
  assert.equal(xhr.aborted, true);
  assert.equal(sent, 0);
  await fetch('/archon/api/v1/config');
  assert.equal(sent, 1);
  assert.equal(read().captured.length, 2);
  assert.equal(window.fetch, originalFetch);
  assert.equal(Xhr.prototype.send, originalSend);
  install();
  assert.equal(read().captured.length, 0);
  await fetch(url, {method: 'POST', body: '{}'});
  assert.equal(sent, 2);
})().catch(error => {console.error(error); process.exitCode = 1;});
"""
    )
    result = subprocess.run(
        [node, "-e", script], capture_output=True, text=True, timeout=20, check=False
    )
    assert result.returncode == 0, result.stderr


@pytest.mark.asyncio
@pytest.mark.parametrize("upload_fails", (False, True))
async def test_capture_keeps_all_images_and_restores_hooks_when_upload_fails(
    upload_fails: bool,
) -> None:
    files = []

    class Locator:
        @property
        def first(self):
            return self

        @property
        def last(self):
            return self

        async def wait_for(self, **_kwargs):
            return None

        async def set_input_files(self, inputs):
            files.extend(inputs)
            if upload_fails:
                raise RuntimeError("upload failed")

        async def click(self, **_kwargs):
            return None

        async def type(self, *_args, **_kwargs):
            return None

    page = AsyncMock()
    page.locator = lambda *_args: Locator()
    page.evaluate.return_value = {}
    session = _Session("account", object(), object(), page, "camoufox", "", "", "")
    transport = MinmaxOfficialBrowserTransport("https://agent.minimax.io")
    transport._session_for = AsyncMock(return_value=session)
    transport._inject_context = AsyncMock()
    invocation = transport.capture_official_message(
        {"user_id": "user", "token": "token"},
        "",
        images=[
            {"data_url": "data:image/png;base64,YQ==", "file_name": "one.png"},
            {"data_url": "data:image/png;base64,Yg==", "file_name": "two.png"},
        ],
        content="Describe the images",
        conversation_id="1",
        agent_id="agent",
        model={},
    )
    if upload_fails:
        with pytest.raises(RuntimeError, match="upload failed"):
            await invocation
    else:
        await invocation
    assert [entry["buffer"] for entry in files] == [b"a", b"b"]
    assert page.evaluate.await_args_list[-1].args == (_RESTORE_MESSAGE_HOOK,)
    transport._unregister_budget_evictors()


@pytest.mark.asyncio
async def test_captured_media_replay_keeps_full_caller_context_and_model(monkeypatch) -> None:
    captured = {
        "content": "short UI prompt",
        "model": {"model_id": "wrong-default"},
        "attachments": [{"upload_id": "image-one"}],
    }
    browser = AsyncMock()
    browser.request.side_effect = [{"status": 200, "body": '{"session_id":"123"}'}]
    browser.capture_official_message.return_value = {
        "url": "https://agent-stream.minimax.io/minimax-cloud/api/v1/session/123/message",
        "body": captured,
    }
    monkeypatch.setattr(minmax, "official_browser_transport", browser)
    prompt = "Original caller context " * 20 + "[Function result call_id=one] completed"
    command = {
        "schemaVersion": 1,
        "requestId": "media-test",
        "model": "MiniMax-M3.1-Flash-Preview",
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt},
                    {"type": "input_image", "image_url": "data:image/png;base64,YQ=="},
                ],
            }
        ],
        "generation": {},
        "reasoning": {},
        "tools": [],
        "controls": {},
        "providerOptions": {"agent_id": "agent"},
    }
    method, _path, body = await minmax._semantic_chat_input(
        {}, command, minmax._default_runtime_plan(), ""
    )
    replay = json.loads(body)
    assert method == "POST"
    assert prompt in replay["content"]
    assert replay["model"]["model_id"] == command["model"]
    assert replay["attachments"] == captured["attachments"]
    assert captured["content"] == "short UI prompt"
