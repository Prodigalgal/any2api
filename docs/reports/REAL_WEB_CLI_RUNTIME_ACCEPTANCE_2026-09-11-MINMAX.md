# MiniMax Runtime 真实验收记录（2026-09-11）

## 结论

MiniMax Runtime 的页面桥、账号租约、SSE 解码和文本 completion 当前正常；连续请求已证明租约可以在多个 ACTIVE 账号之间切换。当前不能标记为稳定 Ready：`MiniMax-M3` 的 24 小时滚动成功率仍为约 87.1%，`MiniMax-M2.7` 仍为约 31.4%，且本观察窗口尚未取得自然 `daily_checkin` 完成事件。

## 发布与运行环境

| 项目 | 结果 |
|---|---|
| Source commit | `ce2ee60` |
| GitOps | `60cd8c1` |
| Argo CD | `Synced / Healthy / Succeeded`，revision `60cd8c16...` |
| K8S | `kubernetes-admin@sg-osaka-dualstack` / `any2api` |
| Pod | server、automation、web、PostgreSQL、Redis 均 Ready，重启数为 0 |
| OOM | 当前没有 `OOMKilling` 事件 |

## K8S 真实请求

请求从 Automation Pod 内经 `http://any2api-server:8080` Service 发起，使用现有公共授权，不输出密钥、账号凭据或上游原始内容。

| 请求 | 结果 |
|---|---|
| `MiniMax-M3` SSE | HTTP 200，约 32.7s，5 个 SSE 帧、包含 `[DONE]`、无错误事件 |
| `MiniMax-M3` 非流式 #1 | HTTP 200，约 32.8s，完整文本 completion |
| `MiniMax-M3` 非流式 #2 | HTTP 200，约 50.7s，完整文本 completion |

服务端 telemetry 均为 `attempt=1/status=SUCCEEDED`。三个请求分别使用三个不同的脱敏账号 ID，证明账号租约真实切换；没有通过失败重试制造切换样本。

## 当前目录状态

| 模型 | 可用账号 | 配额受限账号 | 探针 | 运行态 | 滚动成功率 |
|---|---:|---:|---|---|---:|
| `MiniMax-M3` | 7 | 0 | `READY` | `DEGRADED` | 87.1% |
| `MiniMax-M2.7` | 7 | 0 | `READY` | `DEGRADED` | 31.4% |
| `MiniMax-M2.7-highspeed` | 7 | 0 | `READY` | `READY` | 90.9% |

历史 `quota_exhausted` 样本仍位于 24 小时窗口内，不能通过清理历史数据或降低阈值隐藏。当前新请求已能正常使用额度；后续需等待自然窗口滚动，并取得 `daily_checkin` 的真实成功/失败结果。

## 能力边界

- 文本非流式和 SSE：通过。
- 图片 Runtime：此前已取得真实 SSE 通过证据，仍按已声明能力执行。
- 账号保活：由统一生命周期调度执行；本记录不把手动请求冒充自然保活。
- 每日打卡：代码和通用 `daily_checkin` 语义已存在，但本观察窗口没有自然完成事件，因此保持未闭环状态。
- API Channel：不在本记录范围内，继续后置。
