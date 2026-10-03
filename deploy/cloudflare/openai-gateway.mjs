const PUBLIC_HOST = "any2api.mnnu.eu.org";
const ORIGIN_HOST = "any2api-direct.mnnu.eu.org";

export function isOpenAiPath(pathname) {
  return /^\/(?:v1|[a-z][a-z0-9_-]{1,31}\/v1)(?:\/|$)/.test(pathname);
}

export async function proxyRequest(request, originFetch = fetch) {
  const url = new URL(request.url);
  if (url.hostname !== PUBLIC_HOST || !isOpenAiPath(url.pathname)) {
    return originFetch(request);
  }
  url.hostname = ORIGIN_HOST;
  const forwarded = new Request(url, request);
  forwarded.headers.delete("host");
  try {
    // Return the body stream directly, preserving native status, headers, and
    // cancellation. Buffering here would break SSE and long agent requests.
    return await originFetch(forwarded, { redirect: "manual", cache: "no-store" });
  } catch (error) {
    if (request.signal.aborted) throw error;
    const requestedId = request.headers.get("x-request-id") || "";
    const requestId = /^[A-Za-z0-9_-]{1,80}$/.test(requestedId)
      ? requestedId : crypto.randomUUID();
    return Response.json({ error: {
      type: "upstream_error", code: "upstream_error",
      message: "edge could not reach the gateway", request_id: requestId, retryable: true,
    } }, { status: 502, headers: { "cache-control": "no-store" } });
  }
}

export default { fetch(request) { return proxyRequest(request); } };
