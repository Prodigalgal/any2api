# DeepSeek / MiMo Runtime 真实验收记录（2026-09-11）

## 发布与运行环境

| 项目 | 结果 |
|---|---|
| Source commit | `ce2ee60` |
| GitOps | `60cd8c1` |
| Argo CD | `Synced / Healthy / Succeeded`，revision `60cd8c16...` |
| K8S | `kubernetes-admin@sg-osaka-dualstack` / `any2api` |
| Pod / OOM | 业务 Pod 全部 Ready、重启数为 0；没有新的 `OOMKilling` 事件 |

## DeepSeek

`deepseek/default` 在 Automation Pod 内发起真实 SSE：HTTP 200，耗时约 125.8 秒，首个数据帧约 123.2 秒，11 个 SSE 帧、包含 `[DONE]`、无错误事件。服务端 telemetry 为 `attempt=1/status=SUCCEEDED`，`duration_ms=125543`、`ttfb_ms=122098`、`generation_ms=2623`。

当前目录仍为 `available=true / probe=READY / circuit=CLOSED`，但运行态为 `DEGRADED`，滚动成功率 100%、P95 约 125.7 秒，超过全局 60 秒 Ready 门槛。该延迟主要发生在上游首字节之前，不是 Runtime 解码或 `[DONE]` 判定造成；不能通过降低阈值把它标为稳定 Ready。DeepSeek 当前能力仍为 text-only，图片输入按契约拒绝。

## MiMo

`mimo/mimo-v2.5` 的 32x32 图片 SSE 在 K8S 内真实通过：HTTP 200，约 26.7 秒，24 个 SSE 帧、包含 `[DONE]`、无错误事件；服务端 `attempt=1/status=SUCCEEDED`，`duration_ms=26034`、`ttfb_ms=21408`、`generation_ms=3826`。

当前 `mimo-v2.5` 为 `available=true / probe=READY / circuit=CLOSED`，但滚动成功率约 85.7%、P95 约 30.5 秒，仍为 `DEGRADED`；`mimo-v2.5-pro` 和其他已发现模型大多为 Ready。MiMo 当前有 57 个 ACTIVE/enabled 且未过期账号，最近 12 小时生命周期事件包含 110 次成功 keepalive；图片通过不扩展为文档、音频或视频能力，文件输入仍按目录契约拒绝。

## 结论

DeepSeek 和 MiMo 的当前 Runtime 协议与账号租约链路没有本次回归；剩余问题分别是 DeepSeek 上游高首字节延迟、MiMo `mimo-v2.5` 历史失败样本，以及账号健康窗口自然滚动。API Channel 继续后置。
