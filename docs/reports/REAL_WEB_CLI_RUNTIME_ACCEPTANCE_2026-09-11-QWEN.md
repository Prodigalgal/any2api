# Qwen Runtime 真实验收记录（2026-09-11）

## 结论

Qwen Runtime 当前不能标记为全链路 Ready。文本推理、模型发现、账号保活，以及 32x32 图片的非流式和 SSE completion 均已有成功证据；但 1x1 图片仍会被上游返回 `invalid_input`，图片尺寸边界和持续稳定窗口尚未完成。

这次结果不能解释为“Qwen 账号全部没额度”：账号表有 47 个 `ACTIVE/enabled` 账号，当前模型目录的 `quota_limited_account_count` 为 0，且同一发布上的文本模型探针成功。

## 发布与运行环境

| 项目 | 结果 |
|---|---|
| Source commit | `ce2ee60`（补齐 Qwen 原生上传对象的 `uploadTaskId`） |
| CI | `34552691975`，Automation、Backend、Web 质量检查及镜像构建均成功 |
| GitOps | `60cd8c1`，三套 Any2API 镜像均切换到 `sha-ce2ee60...` |
| Argo CD | `Synced / Healthy / Succeeded`，revision `60cd8c16...` |
| K8S Pod | server、automation、web、PostgreSQL、Redis 均 Ready，重启次数均为 0 |
| OOM | `reason=OOMKilling` 无事件；本次复测后未出现新的 OOM |

## 请求翻译边界

Qwen 不接收 OpenAI 请求原文作为上游 payload。Java 侧将公共请求解析为 canonical fields、`generation`、`reasoning`、`tools`、`providerOptions` 和显式 `controls`，再通过统一 Action 发送给 Runtime；Python Qwen adapter 负责以下厂商特有转换：

1. 创建 `/api/v2/chats/new` 会话。
2. 图片从 inline base64 解析，向 `/api/v2/files/getstsToken` 获取临时上传授权，并在同一账号浏览器上下文内完成 OSS 上传。
3. 将上传返回的 Qwen file object 放入 `/api/v2/chat/completions` 的消息 `files` 字段。
4. 将思考模式、搜索开关、token 别名和消息链翻译为 Qwen 的 `feature_config` 与上游消息结构。
5. 将上游 SSE 事件翻译为统一 canonical events；`rawRequest` 不跨越 Action 边界。

因此上游字段、会话、上传、签名和事件变化只影响 Qwen adapter，不应扩散到公共 Controller、账号租约或统一响应渲染器。

## 真实证据

### 模型发现与文本

- `/v1/models` 当前发现 6 个 Qwen 模型，均报告 `text,image` 输入能力；模型目录为运行态快照，不能代替图片成功验收。
- Qwen 文本模型探针在当前 K8S 发布上成功：`qwen3.5-omni-plus`，HTTP 200，实际输出事件和上游 usage 均被记录。
- 由于图片失败样本仍存在，Qwen 模型健康状态当前为 `DEGRADED`，不将目录中的 `available=true` 等同于多模态 Ready。

### 图片复测

使用 inline base64 图片，并通过公开 `/v1/chat/completions` 进入 K8S Runtime：

| 尝试 | 上游证据 | 网关结果 |
|---|---|---|
| 历史复测 | HTTP 200，`text/event-stream`，392 bytes，2 个 JSON frame，仅存在 `error` 字段，无文本、无终止事件 | `provider_upstream_error`，随后发生重试 |
| 当前发布 `a99ec65` | 同样的 HTTP 200、392 bytes、不完整 `error` 流；`qwen_semantic_command_shape` 确认 `image_count=1`，上传结果 `file_count=1` | HTTP 400，`invalid_request_error`；服务端 `attempt=1`，未发生换账号重试 |
| 当前发布 `ce2ee60`，32x32 红色 PNG | HTTP 200，`text/event-stream`，4446 bytes；`image_count=1`、`file_count=1`，上传字段包含 `uploadTaskId` | HTTP 200，返回 19 字符文本；服务端 `attempt=1`、`duration_ms=232102`、`ttfb_ms=223873`、`usage_source=UPSTREAM` |
| 当前发布 `ce2ee60`，32x32 红色 PNG，`stream=true` | HTTP 200，`text/event-stream`，上游响应 3412 bytes；`image_count=1`、`file_count=1`，无 `completion_error` | HTTP 200，39 个 SSE 帧、38 个 JSON 帧、包含 `[DONE]`、无错误事件；服务端 `attempt=1`、`duration_ms=222681`、`ttfb_ms=205899`、`usage_source=UPSTREAM` |

Qwen automation 对 1x1 图片的脱敏诊断为：`frames=2 json=2 terminal=False text_values=0 fields=error statuses=- ret=-`，错误码为 `invalid_input`；当前发布将这个上游 SSE 错误映射为不可重试的 `invalid_request_error`。32x32 图片则形成了有效的非流式和 SSE 响应，并由 Server 记录成功 usage；本次 SSE 复测后 `qwen3.5-omni-plus` 仍为 `DEGRADED`，滚动成功率约 57.1%、P95 约 252.9s。当前证据说明有效尺寸图片链路已打通，但不能把 1x1 这类边界输入静默视为成功；仍需明确图片尺寸校验边界、补充多尺寸样本和持续健康窗口，因此 Qwen 暂不标完整 Ready，也不自动注册新账号。

### 生命周期

在最近的运行态窗口内，Qwen `keepalive` 以成功为主；同时存在少量 `automation_transport_error`，需要继续观察。成功保活只证明登录态/模型目录链路可用，不替代图片 completion 验收。

## 已完成的修复

- 上游流中出现 `error` 时，`QwenEventDecoder` 现在直接发出统一 `Failed` 事件，不再把它当成 `empty_model_response` 或伪造完成。
- `invalid_input`、`invalid input`、`bad request` 和 `validation` 现在归类为 `invalid_request_error`，不进入账号切换重试；同时识别 Qwen `ret` 数组/字符串事件，并对验证码类 `ret` 归类为 `captcha_rejected`。
- 图片请求在进入 `/api/v2/chats/new` 前已校验并记录安全形状；上传后只记录字段形状，不记录文件内容、URL、账号或凭据。
- Qwen 原生上传对象补齐 `uploadTaskId`；32x32 图片在 K8S 上取得真实 completion。
- 已补充 `quota_exhausted`、`rate_limited` 的错误分类测试；`rate_limit_exceeded` 不再因宽泛的 `limit` 文本被误判为额度耗尽。
- 六家 Runtime adapter 共用 semantic command 结构校验；厂商 mapper 只消费自己的 canonical sections 和 provider namespace。

## 未通过项与下一步

- 1x1 图片仍会触发上游 `invalid_input`，需要补充尺寸边界校验或取得官方页面对该边界的明确行为；不能因为 32x32 成功就宣称所有图片输入均可用。
- 在补齐多尺寸样本、图片 SSE 完成事件和持续健康窗口观察前，Qwen 不进入完整 `Ready`，也不触发“额度不足就注册”的自动补偿。
- API Channel 不在本记录范围内，继续按 Runtime 全链路完成后再逐厂商实现。
