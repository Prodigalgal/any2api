import sys
import re
from collections import defaultdict

stats = defaultdict(lambda: {"total": 0, "success": 0, "failed": 0, "errors": defaultdict(int), "durations": []})

pat = re.compile(r"inference_finished.*?provider=(\S+)\s+model=(\S+).*?status=(\S+)\s+error_code=(\S*)\s+duration_ms=(\d+)")

for line in sys.stdin:
    m = pat.search(line)
    if m:
        prov, model, status, err_code, dur = m.groups()
        dur = int(dur)
        key = (prov, model)
        stats[key]["total"] += 1
        if status == "SUCCEEDED":
            stats[key]["success"] += 1
        else:
            stats[key]["failed"] += 1
            stats[key]["errors"][err_code or status] += 1
        stats[key]["durations"].append(dur)

header = "{:<10} | {:<32} | {:<6} | {:<8} | {:<8} | {:<8} | {:<8} | {}".format(
    "Provider", "Model", "Total", "Success", "Failed", "Avg(s)", "Max(s)", "Top Errors"
)
print(header)
print("-" * 115)
for (prov, model), data in sorted(stats.items()):
    t = data["total"]
    s = data["success"]
    f = data["failed"]
    durs = data["durations"]
    avg_s = (sum(durs) // len(durs)) / 1000 if durs else 0
    max_s = max(durs) / 1000 if durs else 0
    err_str = ", ".join(f"{k}:{v}" for k, v in data["errors"].items()) if data["errors"] else "none"
    row = "{:<10} | {:<32} | {:<6} | {:<8} | {:<8} | {:<8.1f} | {:<8.1f} | {}".format(
        prov, model, t, s, f, avg_s, max_s, err_str
    )
    print(row)
