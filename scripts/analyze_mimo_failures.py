import sys
import re
from collections import defaultdict

# 统计 mimo 的请求行为
cancelled_durations = []
succeeded_durations = []

pat = re.compile(
    r"inference_finished correlation_id=(\S+) provider=(\S+) model=(\S+) protocol=(\S+) channel=(\S+) attempt=(\d+) account_id=(\S+) status=(\S+) error_code=(\S*) duration_ms=(\d+) queue_ms=(\d+) account_acquire_ms=(\d+) ttfb_ms=(\d+) generation_ms=(\d+) input_tokens=(\d+) output_tokens=(\d+)"
)

for line in sys.stdin:
    m = pat.search(line)
    if not m:
        continue
    (cid, prov, model, proto, chan, att, acc, status, err, dur, q_ms, acc_ms, ttfb, gen, in_tok, out_tok) = m.groups()
    dur = int(dur)
    ttfb = int(ttfb)
    gen = int(gen)
    in_tok = int(in_tok)
    out_tok = int(out_tok)

    if prov == "mimo":
        if status == "SUCCEEDED":
            succeeded_durations.append((dur, ttfb, gen, in_tok, out_tok))
        elif err == "downstream_cancelled":
            cancelled_durations.append((dur, ttfb, gen))

print(f"MiMo Succeeded Count: {len(succeeded_durations)}")
if succeeded_durations:
    avg_dur = sum(x[0] for x in succeeded_durations) / len(succeeded_durations) / 1000
    avg_ttfb = sum(x[1] for x in succeeded_durations) / len(succeeded_durations) / 1000
    avg_gen = sum(x[2] for x in succeeded_durations) / len(succeeded_durations) / 1000
    avg_out = sum(x[4] for x in succeeded_durations) / len(succeeded_durations)
    max_dur = max(x[0] for x in succeeded_durations) / 1000
    print(f"  Avg Total: {avg_dur:.1f}s | Avg TTFB: {avg_ttfb:.1f}s | Avg Gen: {avg_gen:.1f}s | Avg Out Tokens: {avg_out:.1f} | Max Total: {max_dur:.1f}s")

print(f"\nMiMo downstream_cancelled Count: {len(cancelled_durations)}")
if cancelled_durations:
    # 分桶
    buckets = defaultdict(int)
    for dur, ttfb, gen in cancelled_durations:
        s = dur // 1000
        if s < 10:
            buckets["<10s (fast abort)"] += 1
        elif s < 60:
            buckets["10s-60s (short timeout)"] += 1
        elif s < 120:
            buckets["60s-120s (standard timeout)"] += 1
        elif s < 235:
            buckets["120s-235s (long timeout)"] += 1
        else:
            buckets[">=235s (server 240s cut/timeout)"] += 1
    for k, v in sorted(buckets.items()):
        print(f"  {k}: {v}")

