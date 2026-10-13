import urllib.request
import json
import time

URL = "https://any2api.mnnu.eu.org/v1/chat/completions"
HEADERS = {
    "Authorization": "Bearer a2a-Y1pER5KqGY8ByaKrueoOrLtiO3I96T4NI8ubq4XmboY",
    "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0"
}

TEST_MODELS = [
    ("deepseek", "deepseek/default", "reply with DEEPSEEK_OK"),
    ("arena_flash", "arena/gemini-3.5-flash-lite", "reply with ARENA_FLASH_OK"),
    ("arena_max", "arena/Max", "reply with ARENA_MAX_OK"),
    ("minmax", "minmax/MiniMax-M3.1-Flash-Preview", "reply with MINMAX_OK"),
    ("mimo", "mimo/mimo-v2.6-flash", "reply with MIMO_OK"),
    ("glm", "glm/glm-4-flash", "reply with GLM_OK"),
    ("longcat", "longcat/longcat-flash", "reply with LONGCAT_OK"),
    ("grok", "grok_web/grok-3-mini", "reply with GROK_OK"),
]

def test_model(tag, model_id, prompt):
    data = json.dumps({
        "model": model_id,
        "messages": [{"role": "user", "content": prompt}],
        "stream": True
    }).encode("utf-8")
    req = urllib.request.Request(URL, data=data, headers=HEADERS)
    start = time.time()
    first_token_time = None
    chunks = []
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            for line in resp:
                txt = line.decode("utf-8").strip()
                if txt.startswith("data: {") and first_token_time is None:
                    first_token_time = time.time() - start
                chunks.append(txt)
        total_time = time.time() - start
        ttfb = f"{first_token_time:.2f}s" if first_token_time is not None else "N/A"
        print(f"[{tag}] SUCCESS | model={model_id} | total={total_time:.2f}s | TTFB={ttfb} | chunks={len(chunks)}", flush=True)
        return True
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8")[:200]
        print(f"[{tag}] HTTP {e.code} | model={model_id} | error={err}", flush=True)
        return False
    except Exception as e:
        print(f"[{tag}] ERROR {type(e).__name__} | model={model_id} | error={e}", flush=True)
        return False

if __name__ == "__main__":
    results = {}
    for tag, model, prompt in TEST_MODELS:
        results[tag] = test_model(tag, model, prompt)
    print("=" * 60)
    print("SUMMARY:")
    for tag, ok in results.items():
        print(f"  {tag:15s}: {'PASSED' if ok else 'FAILED'}")
