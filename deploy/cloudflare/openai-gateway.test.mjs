import assert from "node:assert/strict";
import test from "node:test";
import worker, { isOpenAiPath, proxyRequest } from "./openai-gateway.mjs";

test("API path boundaries preserve admin and unrelated routes", () => {
  for (const path of ["/v1", "/v1/responses/resp_test", "/mimo/v1/chat/completions"]) {
    assert.equal(isOpenAiPath(path), true);
  }
  for (const path of ["/api/admin/v1/session", "/v10/responses", "/x/v1/models", "/v1other", "/"]) {
    assert.equal(isOpenAiPath(path), false);
  }
});

for (const status of [502, 504]) {
  test(`native ${status} JSON, request ID and authorization remain intact`, async () => {
    const payload = JSON.stringify({ error: { code: "upstream_error", request_id: "request-test" } });
    const request = new Request("https://any2api.mnnu.eu.org/v1/responses", {
      method: "POST", headers: { authorization: "Bearer fixture-only" }, body: "{}",
    });
    const response = await proxyRequest(request, async (forwarded, options) => {
      assert.equal(new URL(forwarded.url).hostname, "any2api-direct.mnnu.eu.org");
      assert.equal(forwarded.headers.get("authorization"), "Bearer fixture-only");
      assert.equal(await forwarded.text(), "{}");
      assert.equal(options.redirect, "manual");
      assert.equal(options.cache, "no-store");
      return new Response(payload, { status, headers: { "content-type": "application/json", "x-request-id": "request-test" } });
    });
    assert.equal(response.status, status);
    assert.equal(response.headers.get("x-request-id"), "request-test");
    assert.equal(await response.text(), payload);
  });
}

test("SSE first chunk is delivered while the upstream remains open", async () => {
  let upstream;
  let cancelled = false;
  const body = new ReadableStream({
    start(controller) { upstream = controller; controller.enqueue(new TextEncoder().encode(": request_id=test\n\n")); },
    cancel() { cancelled = true; },
  });
  const response = await proxyRequest(new Request("https://any2api.mnnu.eu.org/v1/responses"),
    async () => new Response(body, { headers: { "content-type": "text/event-stream" } }));
  const reader = response.body.getReader();
  const first = await reader.read();
  assert.equal(new TextDecoder().decode(first.value), ": request_id=test\n\n");
  assert.ok(upstream);
  await reader.cancel();
  assert.equal(cancelled, true);
});

test("origin connection failure is an OpenAI JSON error", async () => {
  const response = await proxyRequest(new Request("https://any2api.mnnu.eu.org/v1/models", {
    headers: { "x-request-id": "request-test" },
  }), async () => { throw new TypeError("synthetic connection failure"); });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error.request_id, "request-test");
});

test("Cloudflare handler accepts the environment argument", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("fixture-only");
  try {
    const response = await worker.fetch(new Request("https://any2api.mnnu.eu.org/v1/models"), {});
    assert.equal(await response.text(), "fixture-only");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
