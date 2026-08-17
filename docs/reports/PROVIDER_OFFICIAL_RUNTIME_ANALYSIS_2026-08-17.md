# 厂商官方运行时调查报告（2026-08-17）

## 调查方法

本次使用当前官方页面、浏览器网络事件和已加载的 Webpack/Rspack bundle 做只读取证，
并与仓库中的真实调用路径逐一对照。未使用生产凭据，未注册账号，未创建真实对话，
因此“匿名/只读可复现”和“真实账号完整 SSE”严格分开。

## 结论矩阵

| 厂商 | 当前仓库链路 | 动态风险归属 | 本期结论 |
| --- | --- | --- | --- |
| Qwen | 官方页面内请求 | Baxia、Cookie、浏览器与出口组合 | 保持官方浏览器传输；生产 A/B 仍需真实账号 |
| MinMax | 官方 Webpack 函数 | `yy`、`x-timestamp`、`x-signature` | 已动态发现并由浏览器生成 |
| MiMo | 官方 Rspack 请求对象 | service Cookie、phase、timezone 与前端封装 | 本期迁移到官方浏览器运行时 |
| GLM | 官方 bundle 函数 | request context、signature、Aliyun challenge | 本期迁移到官方浏览器运行时 |
| DeepSeek | 浏览器 fetch + Java 头/PoW | `x-client-*`、PoW worker | 保留现状，等待认证 SSE 后再迁移 |
| LongCat | 浏览器 fetch + 手工业务头 | H5Guard、Webpack 请求封装 | 保留现状，主执行域桥接尚未通过 |
| Grok Web | 浏览器会话、WebSocket、SSO | Cloudflare、Statsig、Castle、WebSocket | 已在浏览器链路，无需迁移到新通道 |
| Grok Console | Java WebClient 直连 | SSO Cookie、`x-cluster`、网页请求形态 | 暂不迁移，必须先用真实 SSO 验证 |
| Grok CLI | OpenAI 风格直连 API | access token、client version | 属于相对稳定协议，维持原生实现 |

## MiMo

- 目标请求：`GET /open-apis/bot/config` 与 `POST /open-apis/bot/chat`。
- 当前官方函数：包含 `getConfig`、`completions`、`genUploadInfo` 的 Rspack 对象。
- 发现方式：联合 API path、导出对象能力和函数形态，不匹配 module ID。
- 现场诊断曾观察到 module ID `80032`，该数字只作为证据，不进入实现。
- Camoufox 只读 smoke 返回 HTTP 200、22 个模型配置，并生成 schema `1` 的
  `browser_execution_context`；transport mode 为 `official_browser_runtime`。
- Java 不再生成 `x-timezone` 或拼接 phase query；模型发现兼容当前
  `modelConfigListNg` 和旧 `modelConfigList`。
- 媒体上传暂保留原浏览器 transport，避免未经大文件内存测试就扩大变更面。
- 缺口：尚无真实账号 completion 完整 SSE 证据，不能据此发布生产。

## GLM

- 目标请求：创建 chat、生成 request context/signature、调用 `/api/v2/chat/completions`。
- 当前页面构建：`prod-fe-1.1.85`；仓库旧默认值为 `1.1.79`，证明本地常量会漂移。
- 动态发现函数职责：`new_chat`、`request_context`、`sign`、`completion`；现场混淆名曾为
  `dae`、`vre`、`_re`、`Cfe`，实现不保存这些名字。
- 直接 `route.fulfill` 替换脚本的方案经实测未进入已执行模块，已撤销；当前方案读取同一
  页面当前 asset，在浏览器 origin 内追加临时导出并调用官方函数。
- Patchright 只读 smoke 已证明四个函数可发现、request ID 可生成、signature shape 正常，
  且能捕获运行时指纹。
- Java 已删除 `GlmSigner`、`GlmCaptchaClient`、signature key 和 frontend version 配置。
- 缺口：真实 token、Aliyun challenge 和完整 SSE 仍需账号验收。

## DeepSeek

- 当前观察 client version 为 `2.3.0`。
- 普通 page fetch 不会自动补齐官方 `x-client-*`；官方 app 对象中的 HTTP client 会生成。
- 当前 bundle 还暴露官方 WebWorker PoW solver 和 `X-DS-PoW-Response` 编码路径。
- 仓库当前虽然在 BrowserTransport 中发送请求，但 `x-client-*` 和 PoW 仍由 Java 维护。
- 注册成功后持久化账号级 proxy affinity 和成功重试节点的 offset；模型发现、推理和重新授权
  不再按 request ID 切换出口。
- 只读 settings 路径可复现；认证 completion 未验证，因此本期不替换，避免重复请求或
  把空流误报成成功。

## LongCat

- 当前页面会请求 `configListUnlogin`，并加载包含 session-create 和 chat-completion 的模块。
- 现场诊断曾观察 module ID `54953`、`67707`，但不能依赖这些数字。
- Patchright 的 `page.evaluate` 位于隔离执行域；页面 bootstrap 后又移除了公开的
  `webpackChunk*` 引用，精确官方函数桥接未通过自动化复核。
- Camoufox 现场访问曾出现 `NS_ERROR_NET_TIMEOUT`，也没有真实登录态完成 SSE。
- 因证据不足，本期撤回官方 runtime 替换，保留原有 transport；只新增注册与重新授权的
  确定性代理亲和，防止同一账号漂移出口。

## Grok

- Grok Web 已通过 `BrowserTransportClient` 维护 SSO Cookie、浏览器上下文、Cloudflare
  clearance、WebSocket 与媒体链路；它已经属于浏览器传输，不需要重复建设。
- Grok Web 仍有 Statsig 与部分业务 Header 由 Java 生成，后续只有在真实 SSO A/B 证明
  官方 runtime 更稳定时才迁移。
- Grok Console 当前由 Java WebClient 和 Python curl 直接调用 `/v1/responses`，请求中的
  `x-cluster`、Origin/Referer/Fetch Metadata 仍需维护；缺少真实 SSO 证据，暂不改写。
- Grok CLI 使用 `cli-chat-proxy.grok.com/v1` 的稳定协议；没有证据表明浏览器化能降低风险，
  所以保持原生 API adapter。

## 发布门禁

1. MiMo 与 GLM 分别使用真实账号完成一次非流式模型发现、一次真实流式对话和认证状态回写。
2. 同一账号在注册、推理、重新授权三阶段记录相同的 proxy affinity 与出口观测值。
3. 重启 automation 后恢复 `browser_execution_context`，再次完成对话且不触发额外验证码。
4. 对验证码、HTTP 401/403/429、bundle 发现失败和超时分别验证错误分类与账号状态推进。
5. LongCat、DeepSeek、Grok Console 在上述真实证据完成前不得切换默认 transport。
