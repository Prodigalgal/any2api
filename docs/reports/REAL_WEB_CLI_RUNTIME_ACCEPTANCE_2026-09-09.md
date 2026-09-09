# Web API/CLI 反代真实验收记录（2026-09-09）

## 结论先行

截至本记录，系统已经在 K8S 中运行统一的浏览器 Runtime 制品，但还不能标记为“全流程、全业务、全厂商 Ready”。

已形成真实 completion 的厂商是 DeepSeek、GLM、LongCat 和 Qwen 文本链路。Qwen 图片链路已经越过 STS/OSS 上传签名错误，但上游仍返回空模型结果并触发 `account_unavailable`。MiMo 的页面请求真实到达上游但返回 `401 credential_rejected`；MinMax 的单账号探测已经越过本地 30 秒超时，却在约 72 秒后因浏览器流连接提前关闭失败；Grok Build、Grok Web、Grok Console 当前被 Provider 开关阻断且没有可执行账号。

因此，本轮完成的是“真实阻断定位和可修复代码发布”，不是全厂商验收通过。

## 验收口径

对外仍提供 OpenAI-compatible API，但上游不是厂商公开渠道 API，而是厂商 Web 产品或 CLI 工具实际使用的 Web API、页面 `fetch`、页面 WebSocket 和浏览器会话。Camoufox/浏览器 Runtime 负责承载真实页面上下文、Cookie、页面状态、代理和上游请求；Java 只负责统一协议、账号租约、重试、错误分类和观测。

本记录不包含账号邮箱、账号 ID、Cookie、Token、代理地址、完整请求体或模型输出正文。只记录可复核的 HTTP 状态、稳定错误分类、请求关联 ID、镜像提交和 K8S 状态。

## 目标环境与制品

| 项目 | 结果 |
|---|---|
| K8S context | `kubernetes-admin@sg-osaka-dualstack` |
| namespace | `any2api` |
| 集群内服务入口 | `http://any2api-server:8080` |
| 代码修复提交 | `0f0f931`，`fix: align minmax account probe timeout` |
| CI | GitHub Actions `Build and deploy` run `34259432200`，质量检查、三张业务镜像构建和 GitOps 更新均成功 |
| 代码制品镜像 | `server-sha-0f0f931dc09e99c84595409ea21fc221a02df725`、`automation-sha-0f0f931dc09e99c84595409ea21fc221a02df725`、`web-sha-0f0f931dc09e99c84595409ea21fc221a02df725` |
| Argo CD | Application `any2api` 已 `Synced / Healthy / Succeeded`；本次代码制品同步 revision 为 `7bcbfcccfb9e945da5451003bdb5c7adb8e8b24c` |
| Pod | server、automation、web、PostgreSQL、Redis 均为 `Running`；业务 Pod `Ready=true` |
| 账号变更 | 未批量启用 Provider，未批量激活、导入、删除或重置账号 |

## 厂商就绪矩阵

| Provider | 文本真实验证 | 已声明多模态 | 当前运行态 | 结论 |
|---|---|---|---|---|
| DeepSeek Web | K8S 请求 `k8s-ready-20260908-memoryfix-deepseek` 返回 HTTP 200，内容为 `READY`；telemetry 为 `SUCCEEDED` | 当前目录仅声明 text | 16 个账号 `ACTIVE/enabled` | 文本链路 Ready；未完成多模态验收 |
| GLM Web | K8S 请求 `k8s-ready-20260908-dc39-glm-text` 返回 HTTP 200；telemetry 为 `SUCCEEDED` | 当前目录仅声明 text | 23 个账号 `ACTIVE/enabled` | 文本链路 Ready；未完成多模态验收 |
| LongCat Web/CLI | 修复原始浏览器帧解码后，模型探测为 `READY`；公网 API 请求 `k8s-ready-20260909-longcat-public` 返回 HTTP 200、`LONGCAT_READY`，telemetry 为 `SUCCEEDED` | 当前目录仅声明 text | 26 个账号 `ACTIVE/enabled` | 文本链路 Ready；未完成多模态验收 |
| Qwen Web | `k8s-ready-20260908-qwen-bodyfix-text` 返回 HTTP 200；非流式和 SSE 文本均形成 completion | image；当前目录没有 file/audio/video 可执行声明 | 47 个账号 `ACTIVE/enabled`；文本模型探测可 Ready | 文本链路 Ready；图片仍未 Ready |
| MiMo Web | 使用页面同源 `fetch` 的真实请求到达 `/open-apis/bot/chat`，返回 HTTP 401；错误分类 `credential_rejected` | image | 当前盘点为 55 个 `ACTIVE/enabled`、2 个 `EXPIRED/disabled`；不是凭据有效性的证明 | 未 Ready，需重新获取有效 Web 会话凭据 |
| MinMax Web/CLI | 代码将账号探测窗口从默认 30 秒提高到 2 分钟；K8S 单账号探测 HTTP 200，但 `ready=false`、`provider_transport_error`，耗时约 72 秒；Automation 日志显示内部流连接提前关闭 | image | 31 个账号均未启用，其中 30 个 `PENDING`、1 个 `DISABLED`；已知错误主要为 `quota_exhausted` | 未 Ready，需继续修正浏览器流桥/上游请求并准备可用账号 |
| Grok Build | 未执行到上游请求；Provider 被禁用 | 当前无可执行样本 | `grok` Provider disabled；821 个账号均为 `PENDING/disabled` | 未 Ready，需明确启用策略和有效 SSO/账号 |
| Grok Web | 未执行到上游请求；Provider 被禁用 | 当前无可执行样本 | `grok_web` Provider disabled；821 个账号均为 `PENDING/disabled` | 未 Ready，需明确启用策略和有效 SSO/账号 |
| Grok Console | 未执行到上游请求；Provider 被禁用 | 当前无可执行样本 | `grok_console` Provider disabled；821 个账号均为 `PENDING/disabled` | 未 Ready，需明确启用策略和有效 SSO/账号 |

## Qwen 图片链路的最新结论

Qwen OSS V4 签名已按上游规则修正：签名密钥前缀、`/<bucket>/<object>` 规范资源路径、规范请求头和空 `AdditionalHeaders` 均已对齐。官方参考：[Alibaba Cloud OSS V4 签名文档](https://www.alibabacloud.com/help/en/oss/developer-reference/recommend-to-use-signature-version-4)。

在 `a9f0296` 制品上执行的 `k8s-ready-20260909-qwen-image-signfix` 记录显示：STS 获取、页面内 OSS 上传和 Qwen chat completion 页面请求均已到达；浏览器 completion 请求返回 HTTP 200 SSE，但选中账号没有形成可用模型输出，经过账号重试后公共请求最终返回 HTTP 503、`account_unavailable`。因此，当前阻断点已经从 OSS 签名迁移到上游模型响应/账号可用性，不能把图片能力标记为通过。

## MinMax 超时修复与复测

`MinmaxProvider.accountProbeTimeout()` 现在显式使用 2 分钟，并由 `MinmaxProtocolTest` 固定该契约。部署前的真实探测在约 30 秒时被 `InferenceReadinessProbe` 取消；部署后的同一样本耗时约 72 秒，服务端收到 `PrematureCloseException`，Automation 端没有产生可用流事件。这个结果证明代码侧的 30 秒探测窗口已修复，但没有证明 MinMax 上游或当前账号可用。

## 当前全局阻断项

1. MiMo：现有 Web 会话凭据被上游 401 拒绝，必须提供新的有效登录会话或完成受控重新认证。
2. MinMax：当前没有启用账号，单账号浏览器流仍无有效事件；需要继续观察官方前端请求桥/流域名和真实账号状态。
3. Grok 三通道：Provider 开关关闭且全部账号处于待激活状态。没有在本轮批量启用 821 个账号，也没有把禁用状态改成 Ready。
4. Qwen 图片：上传授权已通过，但上游 completion 没有稳定产生有效输出；需要用有效图片和可用账号继续做图片 completion，不能只以 HTTP 200 的 SSE 外壳判定通过。
5. 文件、音频、视频：当前模型目录和适配器没有形成可执行的全量能力矩阵；本轮没有把厂商宣传能力当作 Web/CLI 真实证据，也没有伪造支持状态。

## 代码与测试证据

- LongCat 原始浏览器事件直接交给 `LongcatEventDecoder`，不再错误地先经过 `SseDataDecoder`；对应协议回归测试通过。
- MiMo 改为 Camoufox 页面内同源 `fetch`，保留页面 Cookie、`xiaomichatbot_ph` 和 `x-timezone`，并记录无正文诊断；对应 Automation 测试通过。
- Qwen OSS 上传端点规范化、错误码脱敏和 V4 签名修正已合入；多模态契约测试通过。
- MinMax 账号探测超时从接口默认值提升到 2 分钟；Backend 全量 `gradlew test` 通过，Automation 全量 `pytest`、`ruff format --check` 和 `ruff check` 通过，Web CI 构建通过。

## 发布判断

当前发布可以证明“统一浏览器 Runtime 代码已在 K8S 运行并且已完成真实阻断定位”，不能证明“全厂商、全流程、全业务、全多模态 Ready”。在 MiMo 凭据、MinMax 可用账号/流链路和 Grok 启用条件未解决前，不应把系统状态改成全绿，也不应继续扩大账号激活范围。

## 回滚点

本次 MinMax 超时修复只改变账号探测等待窗口和对应单元测试；如需回滚代码，可回到上一已构建并通过 CI 的 `a9f0296` 镜像/GitOps 制品。回滚仍应通过 GitOps 提交不可变镜像 tag，不直接编辑生产 Deployment。
