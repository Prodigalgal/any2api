const workerSource = `
self.onmessage = async (event) => {
  const { token, difficulty, start, step } = event.data;
  const encoder = new TextEncoder();
  const fullBytes = Math.floor(difficulty / 8);
  const remaining = difficulty % 8;
  for (let nonce = start; nonce < Number.MAX_SAFE_INTEGER; nonce += step) {
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(token + ':' + nonce)));
    let valid = true;
    for (let index = 0; index < fullBytes; index++) {
      if (digest[index] !== 0) { valid = false; break; }
    }
    if (valid && remaining > 0 && (digest[fullBytes] & (0xff << (8 - remaining))) !== 0) valid = false;
    if (valid) { self.postMessage({ nonce }); return; }
  }
};
`;

export function solvePow(
  token: string,
  difficulty: number,
  signal?: AbortSignal,
): Promise<number> {
  if (!Number.isInteger(difficulty) || difficulty < 1 || difficulty > 30) {
    return Promise.reject(new Error("Invalid PoW difficulty"));
  }
  const count = Math.min(4, Math.max(1, navigator.hardwareConcurrency || 2));
  const blobUrl = URL.createObjectURL(new Blob([workerSource], { type: "text/javascript" }));
  return new Promise((resolve, reject) => {
    const workers: Worker[] = [];
    let settled = false;
    const finish = (nonce?: number, error?: unknown) => {
      if (settled) return;
      settled = true;
      workers.forEach((worker) => worker.terminate());
      URL.revokeObjectURL(blobUrl);
      signal?.removeEventListener("abort", abortHandler);
      if (typeof nonce === "number") resolve(nonce);
      else reject(error instanceof Error ? error : new Error("PoW worker failed"));
    };
    const abortHandler = () => finish(undefined, new DOMException("PoW cancelled", "AbortError"));
    if (signal?.aborted) {
      abortHandler();
      return;
    }
    signal?.addEventListener("abort", abortHandler, { once: true });
    for (let index = 0; index < count; index++) {
      const worker = new Worker(blobUrl);
      workers.push(worker);
      worker.onmessage = (event: MessageEvent<{ nonce: number }>) => finish(event.data.nonce);
      worker.onerror = (event) => finish(undefined, new Error(event.message));
      worker.postMessage({ token, difficulty, start: index, step: count });
    }
  });
}
