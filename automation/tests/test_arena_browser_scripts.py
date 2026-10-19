import json
import shutil
import subprocess

import pytest

from any2api_automation.providers.arena_browser import (
    _arena_ndjson_stream_script,
    _arena_turbopack_uploader_init_script,
)


def _run_javascript(script: str) -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node is required to execute the Arena page scripts")
    result = subprocess.run(
        [node, "-e", script], capture_output=True, text=True, timeout=10, check=False
    )
    assert result.returncode == 0, result.stderr


def test_page_modules_preserve_upload_and_use_the_official_programmatic_context() -> None:
    _run_javascript(
        """
const assert = require('node:assert/strict');
global.window = global;
global.document = {scripts: []};
window.fetch = async () => {};
const originalFetch = window.fetch;
"""
        + _arena_turbopack_uploader_init_script()
        + """
let defined = 0;
const evaluateChunk = chunk => {
  for (let index = 1; index + 1 < chunk.length; index += 2) {
    chunk[index + 1]({s: definitions => {defined++;}});
  }
};
global.TURBOPACK = {push: evaluateChunk};
let capturedInteraction;
let interactionCalls = 0;
global.TURBOPACK.push(['fixture', 'upload', module => {
  const generateUploadUrl = null, getSignedUrl = null;
  module.s(['uploadFile', 0, file => file.name]);
}, 'interaction', module => {
  module.s([
    'buildProgrammaticClientInteraction', 0, () => {
      interactionCalls++;
      return capturedInteraction = {submitKind: 'programmatic', submitTrusted: null};
    },
    'packClientInteraction', 0, interaction => {
      assert.equal(interaction, capturedInteraction);
      return 'page-owned-context';
    },
  ]);
}]);
assert.equal(defined, 2);
assert.equal(window.__any2apiArenaUploadFile({name: 'image.png'}), 'image.png');
assert.equal(window.__any2apiArenaClientInteraction(), 'page-owned-context');
assert.equal(window.__any2apiArenaClientInteraction(), 'page-owned-context');
assert.equal(interactionCalls, 2);
assert.equal(window.fetch, originalFetch);
assert.equal(window.__any2apiArenaFetchLog, undefined);
"""
    )


@pytest.mark.parametrize(
    "scenario",
    [
        "success",
        "missing_v3",
        "v3_ready_error",
        "v3_deadline",
        "v2_timeout",
        "v2_deadline",
        "v2_cancel",
        "v2_solved",
        "v2_rate_limit",
        "rate_limit",
        "native_success",
        "native_v2_solved",
    ],
)
def test_stream_context_tokens_deadlines_and_widget_cleanup(scenario: str) -> None:
    script = _arena_ndjson_stream_script(
        "__emit", "v3-key", "v2-key", 5000 if scenario in {"v2_deadline", "v2_cancel"} else 50
    )
    _run_javascript(
        "const scenario = "
        + json.dumps(scenario)
        + ";\n"
        + r"""
const assert = require('node:assert/strict');
global.window = global;
const timers = new Set();
const originalSetTimeout = global.setTimeout;
const originalClearTimeout = global.clearTimeout;
global.setTimeout = (callback, delay, ...args) => {
  const timer = originalSetTimeout(() => {timers.delete(timer); callback(...args);}, delay);
  timers.add(timer);
  return timer;
};
global.clearTimeout = timer => {timers.delete(timer); originalClearTimeout(timer);};
const events = [];
const sent = [];
const containers = new Set();
const reset = [];
let syntheticMouseEvents = 0;
window.__emit = async event => {
  events.push(event);
  if (scenario === 'v2_cancel' && event.version === 'v2' && event.state === 'required') {
    setTimeout(() => window.__any2apiArenaStreamControllers.get(event.requestId).abort(), 0);
  }
};
global.document = {
  dispatchEvent() {syntheticMouseEvents++;},
  createElement: () => ({
    style: {}, setAttribute() {}, remove() {containers.delete(this);},
  }),
  body: {appendChild(element) {containers.add(element);}},
};
global.MouseEvent = class {};
let contextCalls = 0;
window.__any2apiArenaClientInteraction = () => `page-context-${++contextCalls}`;
window.grecaptcha = {enterprise: {
  ready: callback => {
    if (scenario === 'v3_ready_error') throw new Error('ready unavailable');
    callback();
  },
  execute: async (key, options) => {
    assert.ok(!scenario.startsWith('native_'), 'native token must not be replaced');
    assert.equal(key, 'v3-key');
    assert.equal(options.action, 'chat_submit');
    if (scenario === 'v3_deadline') return new Promise(() => {});
    return scenario === 'missing_v3' ? '' : 'single-use-v3';
  },
  render: (container, options) => {
    assert.equal(options.sitekey, 'v2-key');
    if (scenario.endsWith('v2_solved')) queueMicrotask(() => options.callback('single-use-v2'));
    return 7;
  },
  reset: id => {reset.push(id);},
}};
global.fetch = async (url, options) => {
  const body = JSON.parse(options.body);
  sent.push(body);
  const failed = (scenario.startsWith('v2_') || scenario === 'native_v2_solved' || scenario === 'rate_limit') && sent.length === 1;
  const status = scenario === 'v2_rate_limit' || scenario === 'rate_limit' ? 429 : 403;
  return {
    ok: !failed, status: failed ? status : 200,
    headers: new Headers({'content-type': 'application/json', 'retry-after': '12'}),
    text: async () => JSON.stringify({error: scenario === 'v2_rate_limit' ? 'prompt failed' : scenario === 'rate_limit' ? 'too many requests' : 'recaptcha validation failed'}),
    body: new ReadableStream({start(controller) {
      controller.enqueue(new TextEncoder().encode('{"answer":"你好"}\n'));
      controller.close();
    }}),
  };
};
"""
        + "\nconst stream = "
        + script
        + ";\n"
        + """
(async () => {
  const started = performance.now();
  await stream({
    requestId: 'fixture', method: 'POST', url: 'https://arena.ai/nextjs-api/stream/create-evaluation',
    headers: {}, body: JSON.stringify({userMessage: {content: '你好'}}),
    timeoutMs: scenario.endsWith('deadline') ? 600 : 2000,
    nativeSubmission: scenario.startsWith('native_')
      ? {cx: 'native-context', recaptchaV3Token: 'native-single-use-v3'} : undefined,
  });
  if (scenario.endsWith('deadline')) assert.ok(performance.now() - started < 1000);
  const errors = events.filter(event => event.type === 'error');
  if (scenario === 'missing_v3' || scenario === 'v3_deadline' || scenario === 'v3_ready_error') {
    assert.equal(sent.length, 0);
    assert.equal(JSON.parse(errors[0].data).code, 'recaptcha_v3_unavailable');
  } else {
    const native = scenario.startsWith('native_');
    assert.equal(sent[0].cx, native ? 'native-context' : 'page-context-1');
    assert.equal(sent[0].recaptchaV3Token, native ? 'native-single-use-v3' : 'single-use-v3');
    assert.equal(sent[0].nativeSubmission, undefined);
    if (native) {
      assert.equal(contextCalls, 0);
    }
    if (scenario.endsWith('v2_solved')) {
      assert.equal(sent.length, 2);
      assert.equal(sent[1].cx, native ? 'native-context' : 'page-context-2');
      assert.equal(sent[1].recaptchaV2Token, 'single-use-v2');
      assert.equal(sent[1].recaptchaV3Token, undefined);
      assert.equal(errors.length, 0);
    } else if (scenario.startsWith('v2_')) {
      assert.equal(sent.length, 1);
      const error = JSON.parse(errors[0].data);
      assert.equal(error.code, 'recaptcha_v2_required');
      assert.equal(error.triggerReasonSource, 'bridge_inferred');
      assert.equal(error.upstreamStatus, scenario === 'v2_rate_limit' ? 429 : 403);
      assert.equal(error.upstreamError, JSON.stringify({error: scenario === 'v2_rate_limit' ? 'prompt failed' : 'recaptcha validation failed'}));
      assert.equal(error.retryAfter, '12');
      assert.equal(events.filter(event => event.type === 'status').at(-1).status, error.upstreamStatus);
    } else if (scenario === 'rate_limit') {
      assert.equal(sent.length, 1);
      assert.equal(JSON.parse(errors[0].data).error, 'too many requests');
      assert.equal(events.filter(event => event.type === 'status').at(-1).status, 429);
    } else {
      assert.equal(sent.length, 1);
      assert.equal(errors.length, 0);
    }
  }
  assert.equal(syntheticMouseEvents, 0, 'browser input must not be mixed with DOM-dispatched mouse events');
  if (scenario.startsWith('v2_') || scenario === 'native_v2_solved') assert.deepEqual(reset, [7]);
  assert.equal(containers.size, 0);
  assert.equal(timers.size, 0);
  assert.equal(window.__any2apiArenaStreamControllers.size, 0);
})().catch(error => {console.error(error); process.exitCode = 1;});
"""
    )
