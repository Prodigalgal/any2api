# Web API/CLI 反代 Runtime 阶段验收记录（2026-09-10）

## 结论

本轮将 Grok、Grok Web、Grok Console 排除在验收范围之外，对 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 的统一浏览器 Runtime 做了 K8S 真实复测。

当前 `0.13.11` 已在 K8S 生产命名空间运行。六家选定模型的文本非流式请求和 SSE 流式请求均形成成功响应；仓库明确声明图片输入的 MiMo、MiniMax、Qwen 也完成了流式图片请求。没有声明图片输入的 DeepSeek、GLM、LongCat 对图片请求返回明确的 HTTP 400，未静默丢弃媒体内容。

因此，本轮可以确认“统一 Runtime 的推理主链路已打通，OOM 防护已生效”，但仍处于持续观察阶段，不能把所有目录模型或文件、音频、视频能力统称为全量 Ready。API Channel 仍按既定计划后置。

## 验收口径

对外提供 OpenAI-compatible 接口，上游实际使用厂商 Web 产品或 CLI 页面中的 Web API、页面 `fetch`、SSE 和浏览器会话；Camoufox Runtime 承载 Cookie、页面状态、代理和同源请求，Java 负责统一协议、账号租约、重试、错误分类与观测。

本记录只保留可复核的状态、计数、耗时和提交信息，不记录邮箱、账号 ID、Cookie、Token、代理地址、完整请求体或模型输出正文。

## 制品与运行态

| 项目 | 结果 |
| --- | --- |
| K8S context | `kubernetes-admin@sg-osaka-dualstack` |
| namespace | `any2api` |
| 源码提交 | `0de3570a5c8e89c80675689c871eed37abaa469b` |
| GitOps 提交 | `f954447d9c9993d54640e8ce798a3d8e1b0bae92` |
| CI | GitHub Actions `34439484062`，质量检查、三类镜像构建和 GitOps 更新均成功 |
| 镜像 | server、web、automation 均为对应 `sha-0de3570...` 不可变镜像 |
| Argo CD | Application `any2api` 为 `Synced / Healthy / Succeeded` |
| Pod | server、automation、web、PostgreSQL、Redis 均 `Running`，业务 Pod `Ready=true`，重启为 0 |

## 真实请求矩阵

### 文本请求

以下选定模型均在当前 `0.13.11` 服务上完成非流式和 `stream=true` 请求：

| Provider | 模型 | 非流式 | SSE 流式 |
| --- | --- | --- | --- |
| DeepSeek | `deepseek/default` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| GLM | `glm/glm-5.2` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| LongCat | `longcat/longcat-flash` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| MiMo | `mimo/mimo-v2.5` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| MiniMax | `minmax/MiniMax-M3` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| Qwen | `qwen/qwen3.7-plus` | HTTP 200，`choices=1` | HTTP 200，有数据、`[DONE]`、无错误事件 |

服务端 `usage_events` 对上述请求均产生成功记录，`inference_finished` telemetry 的成功状态与公共接口结果一致。

### 图片输入

| Provider | 仓库声明 | 流式图片请求 |
| --- | --- | --- |
| MiMo | `text,image -> text` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| MiniMax | `text,image -> text` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| Qwen | `text,image -> text` | HTTP 200，有数据、`[DONE]`、无错误事件 |
| DeepSeek | `text -> text` | HTTP 400，明确拒绝图片块 |
| GLM | `text -> text` | HTTP 400，明确拒绝图片块 |
| LongCat | `text -> text` | HTTP 400，明确拒绝图片块 |

DeepSeek、GLM、LongCat 当前不声明图片能力，拒绝属于契约行为，不计为 Runtime 上游失败。当前没有把文件、音频、视频宣传能力当作已验收能力；只有适配器、上游页面和真实请求证据同时具备时才会扩展矩阵。

## 生命周期与账号运行态

- 新版本日志持续出现 `lifecycle_actions_claimed ... concurrency=2`，每次 claim 上限为 8；这证明新的生命周期 admission 配置已在运行。
- 当前 Pod 从 `2026-09-10 05:10:47Z` 启动后，DeepSeek、MiMo 的保活成功记录持续产生；Qwen 在滚动切换瞬间产生的两条 `automation_transport_error` 均发生在旧动作交接时，之后连续保活成功，未形成新的同类失败。
- Qwen、MiMo、DeepSeek、GLM 的近期自然调度日志均有 `lifecycle_completed`；LongCat 的现有动作仍按账号散布到后续时间窗口，MiniMax 的 `daily_checkin` 也按每日计划等待下一次 due 时间。本报告不把尚未到期的动作提前记为成功。
- `/v1/models` 已返回六家选定模型，六个模型均 `available=true` 且 `probe_status=READY`。目录中的 `runtime.status` 仍可能显示 `DEGRADED`，因为健康窗口为 24 小时，会保留本轮之前的历史失败样本；这不是本轮成功请求被隐藏，也不应通过清理历史数据强行变绿。
- 最近一小时成功推理实际使用的去重账号数为：DeepSeek 3、GLM 4、LongCat 3、MiMo 9、MiniMax 1、Qwen 3；这证明租约会在有可用账号时切换，MiniMax 当前只有 1 个 `ACTIVE/enabled` 账号，暂时没有第二个账号可做切换样本。

## OOM 与资源观测

复测结束后的观测值：automation 约 `1672Mi`，server 约 `458Mi`，web 约 `46Mi`；automation limit 为 `6Gi`。命名空间没有新的 `OOMKilled` 事件，当前业务 Pod 重启数均为 0。

资源配额使用约为 `limits.memory=9600Mi/16Gi`、`requests.memory=3456Mi/6Gi`。automation 日志持续出现 `browser_process_budget ... capacity=3`，说明进程级浏览器预算和 Java 生命周期并发限制同时生效。

## 当前未完成项

1. 继续观察 LongCat 的自然 keepalive 和 MiniMax 的自然 `daily_checkin`，在动作真正执行后补充成功/失败证据；MiniMax 目前仅有 1 个可用账号，账号切换仍需补充第二个有效账号后验证。
2. 等 24 小时健康窗口中的历史失败样本自然退出，并持续确认选定模型的成功率、P95 和可用账号数；不能只修改阈值来制造 Ready。
3. 文件、音频、视频仍按各 Provider 的真实 Web/CLI 支持情况逐项设计和验收，不能从图片支持推导出来。
4. API Channel 继续后置，当前不改变统一 Runtime 的默认路由，也不启用 Grok 三通道。

## 回滚点

本轮源码和 GitOps 均使用不可变提交与镜像。若后续观测确认生命周期 admission 需要回退，应通过 GitOps 回到上一已构建制品；不要直接编辑生产 Deployment，也不要删除历史账号或观测记录。
