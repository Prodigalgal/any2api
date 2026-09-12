# ADR-0009：Provider API Channel 分阶段启用

- 状态：Accepted
- 日期：2026-09-12
- 适用版本：0.17.0
- 关联：ADR-0007《统一 Action 契约并拆分 Runtime / API Channel》、ADR-0008《Provider 边界与 Arena Direct Runtime 闭环》

## 背景

项目代理的是厂商 Web/CLI 上游，而不是厂商公开渠道 API。Runtime 能力依赖页面上下文，资源
消耗高但可以承载注册、重新认证、保活、打卡、验证码和页面专属上传；直接 API 请求更轻，
但仍然受厂商接口、参数、签名、Cookie、风控和额度更新影响。两者不能因为请求形状相似而
共享错误的认证或会话状态。

## 决策

1. Java 只编排 `CanonicalRequest`、账号租约、代理亲和、模式选择、重试/回退和 canonical
   event；Python Provider 负责 Web/CLI API 的路径、请求体、签名、上传、SSE 和错误分类。
2. Provider inference 统一支持两个具体 channel：`api` 与 `camoufox_browser_runtime`。
   生命周期 `register`、`reauthenticate`、`keepalive`、`daily_checkin` 仍固定走 Runtime。
3. `AUTO` 只由 Java 解析为具体的首选模式和可选回退模式；显式 `API` 失败不得静默切到
   Runtime。当前 `AUTO` 对同时支持两种模式的 Provider 采用 API 优先、Runtime 回退。
4. 公共 API 层只提供 HTTPS host allowlist、同源路径校验、有界 HTTP/SSE、代理租约、取消
   清理和受限的 `Set-Cookie` credential patch。Cookie patch 只允许写入合法的非空 Cookie
   名值，用于同一请求链和后续账号快照；禁止用户原始请求体、任意 URL/JavaScript、验证码
   伪造、风控绕过和额度绕过进入上游。
   多步骤 Action 在上游返回 4xx/5xx 时通过 `ApiActionError` 保留状态码和有界脱敏摘要；
   重定向或缺失状态统一视为传输失败。
5. 当前 API 能力边界如下：

| Provider | API 文本/SSE | API 图片 | API 文档/PDF | 关键前置条件 |
|---|---|---|---|---|
| Arena | Direct/Search | 暂不支持 | 暂不支持 | 可附加厂商签发的 reCAPTCHA v3 token；页面专属 uploader 与 V2 escalation 留在 Runtime |
| DeepSeek | 支持 | 暂不支持 | 暂不支持 | token、device ID、session 和厂商 PoW challenge |
| GLM | 支持 | 支持 vision 模型 | 暂不支持 | 当前前端版本/签名参数，captcha ticket 只接受已有厂商签发值 |
| LongCat | 支持 | 支持 | 支持 | Cookie/access token、session 和返回的 file URL/key |
| MiMo | 支持 | 支持 | 暂不支持 | `xiaomichatbot_ph`、signed upload 和 parse 结果 |
| MiniMax | 支持 | 暂不支持 | 暂不支持 | 现有 provider-specific API 签名与账号绑定 |
| Qwen | 支持 | 支持 | 暂不支持 | token；可选已保存的 Baxia 风控请求头和 STS/OSS 上传授权 |

Grok、Grok Web、Grok Console 不属于本次 API rollout 范围。

## 就绪门禁

代码存在、Action binding 存在或本地夹具通过，都不等于运行态 `READY`。每个 API provider
必须分别取得模型发现、文本非流式、文本 SSE，以及其声明媒体的真实 K8S 样本，并同时
核对请求的 `channel=api`、账号租约、错误分类、无浏览器 session、Pod/重启/OOM 状态。未
满足证据的 provider 保持 `DEGRADED` 或使用 Runtime/AUTO，不降低阈值、不清理历史指标制造
就绪结果。

## 后果

- API 请求通常不创建浏览器进程，因此并发成本和 OOM 风险低于 Runtime；API SSE 仍有响应/事件
  上限、背压、超时和取消清理。
- 厂商前端脚本或接口变化仍可能需要适配，但影响面收敛在对应 API handler；Runtime 页面
  行为与 API handler 可以独立回滚。
- API 的多模态支持按真实上传协议逐厂商声明，不能从产品宣传或 Runtime 通过推导出来。
- 注册和账号恢复仍复用 Runtime，这是为了保留页面专属认证、验证码和协议确认流程，不是
  对 API 的隐式回退。
