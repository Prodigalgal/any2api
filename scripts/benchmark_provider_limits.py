import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

SERVER_URL = "http://localhost:18080/v1/chat/completions"
API_KEY = "a2a-Y1pER5KqGY8ByaKrueoOrLtiO3I96T4NI8ubq4XmboY"

TARGET_PROVIDERS = [
    {
        "provider": "mimo",
        "account_id": "e5952b8d-92bf-4cbe-b441-b500a03b3a3b",
        "email": "a2a31omhtbruup@edu.fourmm.bond",
        "model": "mimo/mimo-v2.6-pro",
        "input_scales": [2000, 8000, 32000, 64000],
    },
    {
        "provider": "minmax",
        "account_id": "b937a729-ccc5-4761-9597-c0ce9646a796",
        "email": "a2ar11u8c4upsqskn@edu.omnnu.xyz",
        "model": "minmax/MiniMax-M3",
        "input_scales": [2000, 8000, 16000, 32000],
    },
    {
        "provider": "grok_web",
        "account_id": "81329e76-5099-4fb1-b240-7960a42dab99",
        "email": "a2azhegn1feyq@edu.fourmm.bond",
        "model": "grok_web/grok-3",
        "input_scales": [2000, 8000, 16000],
    },
    {
        "provider": "arena",
        "account_id": "bd83efda-8698-4312-8758-1fe7161f8f2c",
        "email": "a2a4snbr6b9y4x@edu.omnnu.xyz",
        "model": "arena/claude-sonnet-5",
        "input_scales": [2000, 8000, 16000],
    },
    {
        "provider": "glm",
        "account_id": "ab7ec6f1-0724-4d35-9625-4acfd11971c7",
        "email": "a2az9horasv34p@edu.omnnu.xyz",
        "model": "glm/GLM-5-Turbo",
        "input_scales": [2000, 8000, 16000],
    },
    {
        "provider": "longcat",
        "account_id": "0a086d87-0c11-45ed-9fc1-2ec383b51013",
        "email": "a2a0we9bsr4eb@edu.fourmm.bond",
        "model": "longcat/longcat-pro",
        "input_scales": [2000, 8000],
    },
    {
        "provider": "deepseek",
        "account_id": "0497ab4a-368a-4e1b-9407-583bc9f4b6b8",
        "email": "a2a8ixdva4wbrj4hk@edu.fourmm.bond",
        "model": "deepseek/default",
        "input_scales": [2000, 8000],
    },
]


def run_psql(sql: str) -> str:
    cmd = [
        "kubectl", "exec", "-n", "any2api", "any2api-postgres-0", "--",
        "psql", "-U", "any2api", "-d", "any2api", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return res.stdout.strip()


def isolate_target_account(provider: str, target_account_id: str) -> None:
    sql = (
        f"UPDATE accounts SET status = 'COOLDOWN' "
        f"WHERE provider_id = '{provider}' AND id != '{target_account_id}' AND status = 'ACTIVE';"
    )
    output = run_psql(sql)
    print(f"[{provider}] Isolated to {target_account_id}. DB: {output}")


def restore_provider_accounts(provider: str) -> None:
    sql = (
        f"UPDATE accounts SET status = 'ACTIVE' "
        f"WHERE provider_id = '{provider}' AND status = 'COOLDOWN';"
    )
    output = run_psql(sql)
    print(f"[{provider}] Restored accounts. DB: {output}")


def send_chat_completion(
    model: str,
    messages: List[Dict[str, str]],
    max_tokens: Optional[int] = None,
    timeout: int = 240,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": False,
    }
    if max_tokens is not None:
        payload["max_tokens"] = max_tokens

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        SERVER_URL,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
        },
        data=data,
    )

    start_time = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            elapsed = time.time() - start_time
            body = json.loads(res.read().decode("utf-8"))
            return {
                "success": True,
                "status": res.status,
                "elapsed_ms": int(elapsed * 1000),
                "data": body,
            }
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start_time
        err_body = e.read().decode("utf-8", errors="replace")
        return {
            "success": False,
            "status": e.code,
            "elapsed_ms": int(elapsed * 1000),
            "error": err_body,
        }
    except Exception as e:
        elapsed = time.time() - start_time
        return {
            "success": False,
            "status": 0,
            "elapsed_ms": int(elapsed * 1000),
            "error": str(e),
        }


def make_filler_text(approx_tokens: int) -> str:
    # 1 Chinese character / word block is roughly 1-2 tokens.
    unit = "这是一段用于测试系统长上下文承载能力的文本测试数据，包含详细指标数据与验证序列1234567890。"
    repeat = max(1, approx_tokens // 25)
    return (unit + "\n") * repeat


def test_provider(cfg: Dict[str, Any]) -> Dict[str, Any]:
    provider = cfg["provider"]
    account_id = cfg["account_id"]
    model = cfg["model"]
    results: Dict[str, Any] = {
        "provider": provider,
        "model": model,
        "account_id": account_id,
        "multi_turn": [],
        "stress_input": [],
        "summary": {},
    }

    print(f"\n=======================================================")
    print(f"Testing provider: {provider} | Account: {account_id} | Model: {model}")
    print(f"=======================================================")

    isolate_target_account(provider, account_id)
    try:
        # 1. 多轮会话与上下文保持测试 (3 ~ 4 轮)
        print("\n--- Part 1: Multi-turn Conversation Test ---")
        history: List[Dict[str, str]] = []
        secret_key = f"SEC_TAG_{provider.upper()}_8866"

        # Round 1
        history.append({
            "role": "user",
            "content": f"你好！请牢记本次会话的秘密标记是：【{secret_key}】。请只简短回复确认收到该标记即可。",
        })
        r1 = send_chat_completion(model, history, timeout=240)
        print(f"Round 1: success={r1['success']}, time={r1['elapsed_ms']}ms")
        if r1["success"]:
            r1_reply = r1["data"]["choices"][0]["message"]["content"]
            r1_usage = r1["data"].get("usage", {})
            history.append({"role": "assistant", "content": r1_reply})
            results["multi_turn"].append({
                "round": 1,
                "input_tokens": r1_usage.get("prompt_tokens"),
                "output_tokens": r1_usage.get("completion_tokens"),
                "total_tokens": r1_usage.get("total_tokens"),
                "reply_snippet": r1_reply[:100],
                "elapsed_ms": r1["elapsed_ms"],
            })
            print(f"  R1 Reply: {r1_reply[:80]}... | Usage: {r1_usage}")
        else:
            print(f"  R1 Failed: {r1.get('error')}")
            results["multi_turn"].append({"round": 1, "failed": True, "error": r1.get("error")})

        # Round 2
        if r1["success"]:
            time.sleep(2)
            history.append({
                "role": "user",
                "content": f"请回答第一轮中我给你的秘密标记是什么？然后用一句话解释量子纠缠。",
            })
            r2 = send_chat_completion(model, history, timeout=240)
            print(f"Round 2: success={r2['success']}, time={r2['elapsed_ms']}ms")
            if r2["success"]:
                r2_reply = r2["data"]["choices"][0]["message"]["content"]
                r2_usage = r2["data"].get("usage", {})
                history.append({"role": "assistant", "content": r2_reply})
                has_tag = secret_key in r2_reply
                results["multi_turn"].append({
                    "round": 2,
                    "input_tokens": r2_usage.get("prompt_tokens"),
                    "output_tokens": r2_usage.get("completion_tokens"),
                    "total_tokens": r2_usage.get("total_tokens"),
                    "retained_memory": has_tag,
                    "reply_snippet": r2_reply[:100],
                    "elapsed_ms": r2["elapsed_ms"],
                })
                print(f"  R2 Reply: {r2_reply[:80]}... | RetainedTag={has_tag} | Usage: {r2_usage}")
            else:
                print(f"  R2 Failed: {r2.get('error')}")
                results["multi_turn"].append({"round": 2, "failed": True, "error": r2.get("error")})

        # Round 3
        if r1["success"] and r2.get("success"):
            time.sleep(2)
            filler_mid = make_filler_text(1500)
            history.append({
                "role": "user",
                "content": f"下面是一份附带的背景参考资料：\n{filler_mid}\n请根据以上上下文，再次确认我们的秘密标记是什么？并给出数字 100 的平方。",
            })
            r3 = send_chat_completion(model, history, timeout=300)
            print(f"Round 3: success={r3['success']}, time={r3['elapsed_ms']}ms")
            if r3["success"]:
                r3_reply = r3["data"]["choices"][0]["message"]["content"]
                r3_usage = r3["data"].get("usage", {})
                history.append({"role": "assistant", "content": r3_reply})
                has_tag = secret_key in r3_reply
                results["multi_turn"].append({
                    "round": 3,
                    "input_tokens": r3_usage.get("prompt_tokens"),
                    "output_tokens": r3_usage.get("completion_tokens"),
                    "total_tokens": r3_usage.get("total_tokens"),
                    "retained_memory": has_tag,
                    "reply_snippet": r3_reply[:100],
                    "elapsed_ms": r3["elapsed_ms"],
                })
                print(f"  R3 Reply: {r3_reply[:80]}... | RetainedTag={has_tag} | Usage: {r3_usage}")
            else:
                print(f"  R3 Failed: {r3.get('error')}")
                results["multi_turn"].append({"round": 3, "failed": True, "error": r3.get("error")})

        # 2. 单轮长输入阶梯极限测试
        print("\n--- Part 2: Single-turn Scale Limits Test ---")
        for scale in cfg.get("input_scales", [2000, 8000]):
            time.sleep(2)
            filler = make_filler_text(scale)
            test_prompt = [
                {
                    "role": "user",
                    "content": f"请阅读以下文本，然后回复【已成功处理{scale}数据】：\n{filler}",
                }
            ]
            print(f"Testing input scale ~{scale} tokens...")
            scale_res = send_chat_completion(model, test_prompt, timeout=300)
            if scale_res["success"]:
                usage = scale_res["data"].get("usage", {})
                reply = scale_res["data"]["choices"][0]["message"]["content"]
                print(f"  Scale {scale} PASSED: time={scale_res['elapsed_ms']}ms, usage={usage}")
                results["stress_input"].append({
                    "target_scale": scale,
                    "success": True,
                    "actual_prompt_tokens": usage.get("prompt_tokens"),
                    "actual_completion_tokens": usage.get("completion_tokens"),
                    "elapsed_ms": scale_res["elapsed_ms"],
                    "reply_snippet": reply[:80],
                })
            else:
                err_msg = scale_res.get("error", "")[:200]
                print(f"  Scale {scale} FAILED: status={scale_res['status']}, err={err_msg}")
                results["stress_input"].append({
                    "target_scale": scale,
                    "success": False,
                    "status": scale_res["status"],
                    "error": err_msg,
                })
                # 如果小规模失败，跳过更大规模
                break

    finally:
        restore_provider_accounts(provider)

    return results


def main():
    import sys
    target_filter = sys.argv[1].lower() if len(sys.argv) > 1 else None
    all_results = []
    for cfg in TARGET_PROVIDERS:
        if target_filter and cfg["provider"].lower() != target_filter:
            continue
        try:
            res = test_provider(cfg)
            all_results.append(res)
        except Exception as e:
            print(f"Error testing {cfg['provider']}: {e}")
            restore_provider_accounts(cfg["provider"])

    output_path = f"scripts/benchmark_results_{target_filter or 'all'}.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    print(f"\nBenchmark results saved to {output_path}")


if __name__ == "__main__":
    main()
