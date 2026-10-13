import urllib.request
import json
import time

def test_inference(model_name, prompt="Hello, reply with only the word PONG", stream=False):
    url = "https://any2api.mnnu.eu.org/v1/chat/completions"
    headers = {
        "Authorization": "Bearer a2a-Y1pER5KqGY8ByaKrueoOrLtiO3I96T4NI8ubq4XmboY",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    data = json.dumps({
        "model": model_name,
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream
    }).encode("utf-8")

    start = time.time()
    print(f"[{model_name}] Sending request (stream={stream})...")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            if stream:
                first_byte_time = None
                chunks = []
                for line in resp:
                    line_str = line.decode("utf-8")
                    if not first_byte_time and line_str.strip():
                        first_byte_time = time.time() - start
                    chunks.append(line_str)
                elapsed = time.time() - start
                print(f"[{model_name}] STREAM SUCCESS in {elapsed:.2f}s (TTFB: {first_byte_time:.2f}s, lines: {len(chunks)})")
                return True, elapsed, first_byte_time
            else:
                elapsed = time.time() - start
                body = json.loads(resp.read().decode("utf-8"))
                content = body.get("choices", [{}])[0].get("message", {}).get("content", "")
                print(f"[{model_name}] SUCCESS in {elapsed:.2f}s: HTTP {resp.status}")
                print(f"[{model_name}] Content: {content[:100]}")
                return True, elapsed, None
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        err_msg = e.read().decode("utf-8")[:300]
        print(f"[{model_name}] HTTP ERROR {e.code} in {elapsed:.2f}s: {err_msg}")
        return False, elapsed, None
    except Exception as e:
        elapsed = time.time() - start
        print(f"[{model_name}] ERROR {type(e).__name__} in {elapsed:.2f}s: {e}")
        return False, elapsed, None

if __name__ == "__main__":
    test_inference("arena/gemini-3.5-flash-lite", stream=True)
