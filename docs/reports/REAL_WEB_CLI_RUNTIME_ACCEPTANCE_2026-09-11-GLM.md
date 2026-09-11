# GLM Runtime 真实验收记录（2026-09-11）

## 结论

GLM 的文本和视觉 Runtime 链路均已在当前 K8S 制品上取得真实成功；`glm-5.2` 和 `glm-4.6v` 当前达到 Ready 门槛。GLM 不能被概括为“所有目录模型 Ready”：部分历史/实验模型仍由真实探针判定为 `UNAVAILABLE/provider_upstream_error`，`glm-4.7` 和 `glm-5.3` 也因 24 小时窗口指标暂为 `DEGRADED`。

## 发布与运行环境

| 项目 | 结果 |
|---|---|
| Source commit | `ce2ee60` |
| GitOps | `60cd8c1` |
| Argo CD | `Synced / Healthy / Succeeded`，revision `60cd8c16...` |
| K8S | `kubernetes-admin@sg-osaka-dualstack` / `any2api` |
| Pod | server、automation、web、PostgreSQL、Redis 均 Ready，重启数为 0 |
| OOM | 当前没有 `OOMKilling` 事件 |

## 真实图片 SSE

请求从 Automation Pod 内经 `http://any2api-server:8080` Service 发起，使用 32x32 inline PNG，连续三次进入 `glm/glm-4.6v`：

| 请求 | 结果 |
|---|---|
| #1 | HTTP 200，约 46.7s，15 个 SSE 帧、包含 `[DONE]`、无错误事件 |
| #2 | HTTP 200，约 53.8s，12 个 SSE 帧、包含 `[DONE]`、无错误事件 |
| #3 | HTTP 200，约 57.5s，12 个 SSE 帧、包含 `[DONE]`、无错误事件 |

服务端三条 telemetry 均为 `attempt=1/status=SUCCEEDED`，P95 计时分别为服务端真实 Runtime 耗时；三次请求由不同 ACTIVE 账号承载。图片上传和页面同源请求由 GLM Runtime 完成，未调用厂商公开渠道 API。

## 当前目录状态

| 模型 | 输入能力 | 探针 | 运行态 | 滚动成功率 | P95 |
|---|---|---|---|---:|---:|
| `glm-4.6v` | `text,image` | `READY` | `READY` | 90.48% | 53.7s |
| `glm-5.2` | `text` | `READY` | `READY` | 95.0% | 53.0s |
| `glm-4.7` | `text` | `READY` | `DEGRADED` | 100% | 78.4s |
| `glm-5.3` | `text` | `READY` | `DEGRADED` | 75.0% | 48.4s |

`0808-360B-DR`、`GLM-4.1V-Thinking-FlashX`、`deep-research`、`glm-4-air-250414` 和 `zero` 当前为 `available=false`，探针错误分类为 `provider_upstream_error`。这些模型不应因为同一厂商的其他模型成功而被隐式标记 Ready。

## 能力边界与生命周期

- 文本非流式/SSE：`glm-5.2` 已有真实成功证据。
- 图片非流式/SSE：`glm-4.6v` 已有真实成功证据；只对官方目录声明 `vision=true` 的模型开放图片输入。
- 文档：当前 GLM 目录不声明文件输入；有效 PDF 请求由契约层明确拒绝，不静默丢弃文件。
- 账号保活、模型探针和账号租约由统一生命周期/Runtime 调度执行；本记录不把手动探针冒充自然保活。
- API Channel：不在本记录范围内，继续后置。
