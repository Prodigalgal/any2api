# LongCat K8S Runtime 真实验收补充记录（2026-09-11）

## 结论

本次验证针对当前生产 K8S Runtime，不接入 LongCat 公开渠道 API。LongCat 的图片、PDF、DOCX、TXT 已分别取得非流式和 SSE completion 证据；两个不同 ACTIVE/enabled 账号的真实账号探针均通过。当前 LongCat 已达到本轮已声明模型和图片/文档范围的功能 Ready，保活仍继续由自然调度观察。

## 制品与运行态

| 项目 | 结果 |
| --- | --- |
| K8S context / namespace | `kubernetes-admin@sg-osaka-dualstack` / `any2api` |
| 源码提交 | `09b5202`（LongCat Runtime 映射代码沿用已验收的 `1a7e6b9`） |
| GitOps 提交 | `16eda4f` |
| Argo CD | `Succeeded / Synced / Healthy` |
| 业务 Pod | server、automation、web 均 `Ready=true`，重启数为 0 |
| OOM | 当前没有 `OOMKilling` 事件 |

健康端点 `/healthz`、`/readyz` 和 Actuator readiness 均返回 HTTP 200、`{"status":"UP"}`。

## LongCat 真实请求

| 输入/模型 | 模式 | 结果 |
| --- | --- | --- |
| `longcat/longcat-flash` | 4 次非流式 | 全部 HTTP 200，正常 completion |
| `longcat/longcat-flash` | 2 次 SSE | 全部 HTTP 200，包含 `[DONE]`，无错误事件 |
| TXT `acceptance.txt` | 非流式 | HTTP 200，准确返回附件唯一标识 |
| TXT `acceptance-sse.txt` | SSE | HTTP 200，包含 `[DONE]` 和附件唯一标识，无错误事件 |
| 两个不同 ACTIVE/enabled 账号 | 手动账号探针 | 均 `ready=true`，模型为 `longcat-pro`，耗时约 38.8s / 11.3s |

此前已在同一 Runtime 版本链路取得图片、PDF 和 DOCX 的非流式/SSE completion 证据；本次 TXT 复测推翻了旧的“只能上传、无法解析”观察结论。最近 30 分钟 LongCat 成功请求使用了 9 个不同账号，说明租约选择没有固定在单一账号。

当前模型目录重新读取结果：`longcat-flash`、`longcat-pro`、`longcat-thinking`、`longcat-search`、`longcat-reason-search` 均 `available=true`、探针 `READY`、熔断器 `CLOSED`；`longcat-flash` 成功率约 90%、P95 约 47.6s，已达到当前 Ready 门槛。

LongCat 账号事件页当前返回 26 个账号，26 个为 `ACTIVE/enabled`。选定账号的最近自然 `keepalive` 事件为 `SUCCEEDED / lifecycle_completed`，耗时 `21502ms`，开始时间 `2026-09-10T20:31:32Z`，无错误码；本次未通过手动命令伪造保活结果。

## 能力边界

- 已验证：文本、图片、PDF、DOCX、TXT；图片和文档均使用同一账号页面/代理上下文完成上传与对话。
- 尚未逐项取得真实 completion：`doc`、`xls`、`xlsx`、`pptx` 等其他扩展；不能从 TXT/PDF/DOCX 推导全部文件扩展均可用。
- 音频、视频不属于本轮 LongCat 聊天输入范围。
- 已取得 LongCat 自然 `keepalive` 成功记录；仍继续观察多账号覆盖和 24 小时健康窗口，本记录不把手动账号探针冒充成自然保活证据。

## 后续

继续观察下一批 LongCat 自然保活、文件扩展和 24 小时健康窗口；同时把相同的“每家厂商独立参数映射、独立错误分类、Runtime 实证”口径推进到其余未达到 Ready 的 Provider。API Channel 仍后置。
