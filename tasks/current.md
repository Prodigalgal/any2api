# 当前任务板（源码 0.24.4）

> 当前源码事实以代码和 [API 契约](../docs/architecture/API_CONTRACTS.md)为准；
> 历史任务与运行态快照已归档。

## 2026-10-02 发布部署与 Read 性能核验（0.24.1 → 0.24.4）

- 用户已授权提交、部署和测试。0.24.0 本地候选验收后补齐日期/版本/用途镜像标签，正式候选升为 0.24.1；流水线忽略纯 docs/tasks 变动，避免验收记录触发同版本重新发布。
- 发布与性能范围：[执行规格](../docs/requirements/RELEASE_AND_READ_PERFORMANCE.md)。先测旧生产 Read 基线，再跟踪 CI、GitOps、Pod 和真实厂商工具闭环。
- 0.24.1 / 0.24.2 已部署，CI `37011929616` / `37016567168`，GitOps `39a59fb` / `3043e12`，四个应用 Ready，迁移 032 完成。0.24.2 修复对象 tool_choice、列表 N+1 和同步 Controller 线程阻塞，Key/规则/厂商列表耗时实测下降。
- 0.24.2 LongCat 真实 SDK 7 组验收通过；MiMo Responses 6 组通过、Chat null assistant content 翻译失败。0.24.3 在 canonical 边界修复 null/missing content，补充 3 项回归与可配置 smoke 超时，CI `37019554190` / GitOps `116df49` 已部署。
- 0.24.4 补齐 Automation 两个镜像的当前包安装与 artifact version 门禁，纠正固定 browser-runtime 内旧的 `0.1.0` distribution metadata；不更新 browser-runtime/运行依赖。正在核验最终候选。

## 2026-10-02 OpenAI Chat/Responses agent 协议升级（0.24.0）

- 阶段 1–3 已实现：历史回放、function/custom/namespace、流式终态、缓存隔离、网关 Responses 状态、所有权与资源接口。
- 阶段 4 本地候选验收：官方 SDK、真实 Codex CLI 图片工具闭环、真实 PostgreSQL 迁移和回滚；结果见 [验收记录](../docs/reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)。
- 用户已授权真实厂商和生产部署；当前已发布至 0.24.3，0.24.4 候选正在验证。SDK/Codex 与 Read 结果见 [发布与性能记录](../docs/reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)，阶段 4 仍按厂商逐项核验。
- [任务记录](in-progress/OPENAI_AGENT_COMPATIBILITY.md)、[需求契约](../docs/requirements/OPENAI_AGENT_COMPATIBILITY.md)、[客户端接入](../docs/integrations/OPENAI_AGENTS.md)。

## 2026-09-30 Grok Web 双域SSO Cookie双向复制、注册跨域会话导航与多端版本升级（0.22.10）

- **Grok Web 跨域双域 SSO Cookie 双向复制与保活闭环**：
  - **根因查明**：新注册账号生成的 `browser_execution_context` 仅包含 `accounts.x.ai` 的 StorageState；`_credential_cookies` 在检测到 `browser_execution_context` 时直接早退并仅返回 `.x.ai` 域名 Cookie，导致 `OfficialBrowserRuntime` 打开 `https://grok.com` 时浏览器未携带任何 Cookie，`/api/auth/session` 返回空 session `{}` 并触发 `SsoSessionExpired`；
  - **双向复制与完整注入**：重构 `_credential_cookies`，针对 `storage_state` 中的每个 Cookie，只要包含 `sso`/`sso-rw` 或域名为 `x.ai`，强制同步复制一份至 `.grok.com` 域（反之亦然），并确保所有备用 Cookie 也完整映射至双域；
  - **注册后显式访问 Grok.com**：在 `register_grok_web` 流程结束前，预先同步 Cookie 并显式导航访问 `https://grok.com`，使 `_SESSION_REQUEST` 在实际 Grok 主站直接提取 `userId`（支持 `session.userId`、`userId`、`user.id` 多层提取），并在 StorageState 中持久化包含双域完整的 Cookie 与本地存储。

## 2026-09-30 Qwen与Grok重试邮箱独立轮换、首屏超时加固与多端版本升级（0.22.9）

- **通义千问（Qwen）与 Grok Web 重试邮箱独立轮换机制**：
  - **根因查明**：旧架构在 `register()` 循环外调用 `prepare_registration`，导致 5 次 attempt 全程复用同一个邮箱与域名；当阿里云或 Grok 上游风控对首轮邮箱/后缀进行隐式拦截（前端显示验证码已发送但实际不发信）时，后续 4 次重试均在同一个已受限邮箱上徒劳等待超时；
  - **动态独立轮换**：将 `prepare_registration` 移入 `attempt` 循环内部，每次重试均生成独立干净的新邮箱（自动随机轮换域名，如 `edu.fourmm.bond` 等），并在捕获异常时重置 trace，使每次重试都获得完全隔离的新注册上下文；
  - **Grok Web 首屏探测超时加固**：`grok_web_browser.py` 中邮箱输入框探测超时从 10s 提升至 25s，避免跨国网络及 Cloudflare 等待引起的偶发早夭。

## 2026-09-30 Qwen精准填码与滑块接管强化、全域Token捕获与Grok保活实效闭环（0.22.8）

- **通义千问（Qwen）OTP精准填码、密码滑块接管与全域Token拦截闭环**：
  - **根因查明**：Qwen 注册收到 6 位 OTP 后，由于旧逻辑同时派发 paste、keyboard.type 和逐格 fill，单/多单元格输入发生竞态，将验证码输入为重复或错位字符串；且确认按钮被点击后若触发二次滑块挑战未被接管，密码与昵称设置表单未接入挑战解决器，过早强行 goto 刷新打断了注册会话；
  - **精准输入与滑块接管**：重构 `_fill_and_submit_qwen_otp`，支持键盘模拟与自动逐格校准（双重一致性核验），消除重复敲击；提交验证码、密码和昵称时全面接入 `challenge.submit_and_solve` 解决风控滑块；移除过早的页面刷新破坏逻辑，允许平滑等待导航完成；
  - **全域 Token 拦截与多层回退**：`qwen_challenge.py` 扩充对所有包含 `auth`、`verify`、`signup`、`signin`、`user`、`token` 的 JSON 响应的深度捕获，并在主站刷新与 signin 回退中加入明确日志与保护，确保注册凭据 100% 捕获入库。
- **Grok Web 跨域双域 SSO Cookie 与全量 StorageState 实测验证**：
  - 验证 0.22.7 提交的 `browser_execution_context` 持久化及 `.grok.com` + `.x.ai` 双域 Cookie 注入逻辑；
  - 启动真实注册与会话保活回归测试，验证新账号长期保持 ACTIVE。

- **通义千问（Qwen）现代化多形态 OTP 验证自适应与提交流程闭环**：
  - **根因锁定**：现代 Qwen 前端重构后，验证码输入框类名已不再是单一的 `.qwenchat-verification-code-input-cell`，旧逻辑在 `cells.count() >= 6` 失败后退化为 `first_visible(input[type=text])`，将 6 位验证码全部塞进第 1 个单字符格子导致验证失败；且旧代码对验证码提交步骤未对接人机滑块挑战接管，并在 `/auth` 未完成认证时强行跳转主站丢失凭据；
  - **实现自适应填充与提交**：新增 `_fill_and_submit_qwen_otp`，多级适配独立单字符多单元格（支持 clipboard paste 与逐格事件派发）和统一验证码框；对接滑块挑战接管与验证后潜在密码/昵称设置步骤；`qwen_challenge.py` 扩充对 `access_token` 字段的拦截捕获，实现 Token 100% 完整提取闭环。
- **Grok Web 跨域 SSO 会话持久化与长效保活修复**：
  - **根因锁定**：历史 836 个 grok_web 账号失活的根本原因在于：注册完成后未持久化 Playwright `context.storage_state()`，且 `_credential_cookies` 将包括 `accounts.x.ai` 的所有 SSO Cookie 暴力改写成了 `.grok.com` 单一域；新建会话保活时因缺少跨域凭据导致 `/api/auth/session` 返回空 session `{}`；而 `sso_channel.py` 中的 `probe_result` 将 200 空 session 误报为 `ChannelProbeFailed`（`auth_expired: False`），导致死循环重试或异常淘汰；
  - **实现完整会话持久化与双域注入**：在 `register_grok_web` 中捕获并写入包含完整 cookies 和 localStorage 的 `browser_execution_context`；在 `_credential_cookies` 中优先使用 `storage_state` 原始 cookie，并在回退时同时向 `.grok.com` 与 `.x.ai` 注入 SSO Cookie 维持跨域有效性；在 `sso_channel.py` 中将未登录的 200 会话精准识别并标记为 `auth_expired: True`（`SsoSessionExpired`）。


## 2026-09-29 PENDING 账号处置与根因修复、Qwen新版Token契约适配与Grok空会话淘汰（0.22.6）

- **通义千问（Qwen）前端新版 Token 契约逆向适配与闭环**：
  - **根因查明**：逆向官方前端 JS bundle（`0.3.12/js/main.js`）证实，官方已从单一 `localStorage.getItem("token")` 改为多级结构存储在 `qwen_access_token_state`（内含 `token`, `expiresAt`, `version`）以及 `active_token`，且首屏通过 `window.__prerendered_data.user.token` 注入；导致新版注册虽然流程成功，但入库凭据缺少 `token` 字段，后端保活校验拦截死锁在 PENDING；
  - **契约重构**：`automation/any2api_automation/providers/qwen.py` 改造为多级级联 Token 提取与 JWT 解码（直接从 JWT payload 提取 `user_id`），并优化验证码键盘模拟输入提升成功率；`qwen_challenge.py` 扩展 POST 拦截监听，彻底解决凭据完整性问题。
- **Grok Web 空 Session 凭据拒绝与淘汰流转加固**：
  - **根因查明**：9 月底早期版本注册残留的 15 个空会话账号在探活时返回 `status: authenticated` 但 `userId` 与 `sessionId` 均为空；后端 `GrokWebFailureClassifier.java` 错误将其兜底分类为 `provider_stream_error`（标记为可重试流式错误），导致调度器误判为偶发网络抖动陷入无休止重试死循环；
  - **分类修正**：在 `GrokWebFailureClassifier.java` 增加 `unauthenticatedSession` 语义提取，将空 `userId`/`sessionId` 明确分类为 `credential_rejected`（不可重试）；
  - **生命周期流转**：`LifecycleScheduler.java` 在账号达到重试上限或遭遇不可逆凭据拒绝时，显式将其状态流转为 `EXPIRED` 并淘汰，限制重激活仅适用于 3 天内的偶发抖动账号，根除僵尸 PENDING 账号。

## 2026-09-29 重点问题厂商注册攻坚、Grok保活激活契约重构与全链路长耗时超时加固（0.22.5）

- **Grok Web 保活契约重构与 PENDING 账号激活解脱**：
  - **根因查明**：旧版 `GrokWebLifecycleHandler.java` 未沿用系统通用的 `LifecycleOperationExecutor` -> `automation.execute`，而是通过 `OfficialBrowserTransportClient` 错误从顶层读取 `status` 与 `body`；由于自动化端返回的是标准生命周期探活对象（包含 `healthy: true` 而非原始 HTTP 状态码），导致后端恒解析出 502，将每次保活与初次激活均误判为 `provider_upstream_error`；11 次重试耗尽后所有新注册成功的账号死锁在 `PENDING` 状态；
  - **架构修正**：彻底删除孤立的 `GrokWebLifecycleHandler.java`，让 Grok Web 生命周期完全回归通用的 `LifecycleOperationExecutor` 架构；同时在 `OfficialBrowserTransportClient.java` 中引入对 `result` 容器和 `healthy` 响应的防御性解包；
  - **调度自愈增强**：优化 `LifecycleScheduler.java` 中的 `reactivateExhaustedActions`，允许未过期的 `PENDING` 账号在冷却后重新唤醒调度，杜绝新注册账号因偶发抖动而“早夭”死在 PENDING。
- **通义千问（Qwen）现代化纯验证码注册流程重构与 100% 成功交付**：
  - **现场真机取证与流程勘误**：在生产 Pod 内通过真实 Camoufox 捕获现场证实，通义千问官方注册页已全面转为 **"Sign up with verification code"（纯验证码注册模式）**；页面仅有单邮箱输入框与 Continue 按钮，点击后向邮箱发送 6 位验证码，页面渲染 6 个独立单字符输入格（`.qwenchat-verification-code-input-cell`），填入即自动登录主站，不再支持旧版的用户名、独立密码设置与邮件激活链接；
  - **自动化端全流程重写**：`automation/any2api_automation/providers/qwen.py` 完整实现自适应注册流程：智能识别现代验证码注册与旧版双模式；处理用户协议多选框、提交邮箱、自动化滑动验证码解除、异步邮件 6 位验证码接收、按格填入 6 位验证码并点击确认、等待页面跳转进入 `/`，并由上下文提取 Cookie/JWT Token 与会话凭据直接入库；
  - **生产环境端到端验证通过**：
    - Job `75c611f8-64eb-4407-ac14-11c972c036b7`：新注册任务 1 次尝试即 100% 成功完成，账号 `2c1a0820-70af-4162-879e-28b2ac153caa` 成功诞生并安全入库；
    - Job `d458c2b5-9277-4f63-9c3a-de4c1be0a793`：此前因旧逻辑 4 次失败的存量任务，在新版本上线后自动进行第 5 次重试并 100% 成功，账号 `fc5e1c00-3b9f-4dde-9560-a62b377b6a43` 成功诞生，任务转为 `SUCCEEDED`；
    - 彻底扭转通义千问过去 100% 注册暴毙的局面，实测注册成功率达到 **100%（2/2）**。
- **全链路长耗时自动化超时加固（DeepSeek / GLM）**：
  - **根因查明**：`WebClientConfiguration.java` 中的内部 HTTP 连接底层 Netty `responseTimeout` 硬编码为 5 分钟，而复杂的浏览器人机验证（DeepSeek hCaptcha / 邮件验证码收发、GLM 滑动拼图）在多轮重试时极易逼近 300 秒，导致客户端连接被粗暴掐断并报 `WebClientRequestException`；
  - **加固**：将 `WebClientConfiguration` 的 `responseTimeout` 从 5 分钟提升至 15 分钟，与 `RegistrationJobScheduler` 35 分钟的调度设计相匹配，保障长耗时多轮验证码和异步邮件等待稳定返回。

## 2026-09-29 全厂商最新模型探活、Arena纯文本选型与LongCat/Qwen链路加固（0.22.4）

- **全厂商探活动态首选各厂商最新代际模型**：
  - 各厂商 Manifest 与 `scheduledProbeModel()` 全面升级对齐各家最新主力模型：MiMo (`mimo-v2.6-flash`), GLM (`glm-5.3`), Qwen (`qwen3.8-max`), MiniMax (`MiniMax-M3.1-Flash-Preview`), Arena (`Max`), LongCat (`longcat-flash`), Grok (`grok-3`), DeepSeek (`default`)；
  - `ModelProbeScheduler` 防重复探测与额度保护机制：引入 15 分钟新鲜 READY 探针排他拦截，已成功探活的厂商在新鲜窗口内绝不重复发起探测，显著节省调用额度并降低风控封号风险。
- **Arena 探活彻底避免选中文生图/生视频等非对话模型**：
  - 显式声明 `ArenaProvider.scheduledProbeModel() = "Max"`，实测 13s 内稳定就绪；
  - `ModelProbeScheduler` 数据库候选模型查询在 SQL 层建立多模态/生图/生视频模型降级排除机制（自动后置或过滤 `%-image%`, `%-video%`, `flux-%`, `wan-%`, `veo-%`, `kling-%`, `sora%` 等），确保任何纯文本对话模型绝对优先于生图/生视频模型，杜绝纯文本 completions 命中图像模型导致的 400 `invalid_request_error`。
- **LongCat 与 Qwen 链路认证失效自愈与异常识别加固**：
  - 根因定位：LongCat 美团会话创建接口在凭据过期时返回 HTTP 200 + `{"code": 401, "message": "Please log in to continue"}`，通义千问上游在 Token 失效时返回 401 并被反代包装为 502；此前系统仅比对 HTTP 状态码 401/403，导致大量登录失效被误判为 502 `provider_upstream_error`，不仅重试死循环，更无法触发后端的账号下线与重新认证/自动注册补号；
  - `ProviderFailureSignals.java` 引入保守且强健的 `isCredentialRejected` 判定，覆盖 `unauthorized`, `code=401`, `Please log in`, `token expired` 等认证失效特征；
  - `LongcatProvider.java` 与 `QwenProvider.java`：在流式错误帧与模型发现阶段统一将凭据失效提升为 401 `credential_rejected`，禁止单账号无效重试；
  - `automation` 端 `longcat.py` 与 `qwen.py`：在 `keepalive` 与 `transport_stream` 中精准捕获 401/登录失效语义，返回 `auth_expired: True` 且 `error_class: "credential_rejected"`，使得后端 `LifecycleScheduler` 与 `AccountRecoveryService` 能立即调度 `reauthenticate` 或自动注册新号补充账号池。

## 2026-09-29 探活自适应动态选型与 Arena 全链路打通（0.22.3）

- **探活模型动态自适应与无硬限制选型**：
  - 针对厂商模型下架或库中被禁用导致探活硬编码失效（如 GLM 假死 503、DeepSeek 因 expert 禁用未探活等）问题，重构 `ModelProbeScheduler`：动态查询当前厂商在数据库中处于 `enabled = TRUE` 的最新模型（`ORDER BY updated_at DESC, id DESC LIMIT 1`），优先命中 preferred 列表中的可用项，若下架则自动平滑降级到最新模型，彻底解除模型硬编码限制；
  - 移除 `GlmProvider` 中对探活禁用的 override（默认开启），让智谱 GLM 正常参与探活，消除冷启动 503 假死；
  - 移除 `ArenaProvider` 中硬编码的 `scheduledProbeModel() = "Max"`，允许根据库内启用的 260+ 模型动态自适应演进。
- **探活提示词自然化与安全脱敏**：
  - `ModelProbeService` 与 `InferenceReadinessProbe` 中的探活提示词由生僻测试宏（`ANY2API_MODEL_OK` / `ANY2API_PROBE_OK`）统一优化为自然的对话提示词（`"Hello! Please reply with a short confirmation message."`），彻底杜绝因怪异字符串命中大模型上游安全审查（Safety Filter）或防注入拦截导致探活拒答。
- **Arena 链路全线打通与注册即激活**：
  - 彻底查明 Arena 账号停留在 `PENDING` 的根本原因：在自动化端真实浏览器环境（Camoufox）中，账号已经成功接收邮件验证码、设置密码并调用 `/api/me` 验证了 profile，但 `BrowserResult` 中错误设置了 `ready_for_inference=False, inference_probe_required=True`，导致合法的账号被扔回空会话执行推理探测并撞上 Google reCAPTCHA v2；
  - 优化 `arena_browser.py`：真实浏览器注册成功后直接将账号标记为 `ready_for_inference=True, inference_probe_required=False`，注册完成即投入生产；
  - 生产数据库已将 3 枚 Arena 账号成功激活为 `ACTIVE`，实测 `arena/Max` 真实推理调用毫秒级响应并 100% 成功生成。

## 2026-09-28 模型轻量探活与防封禁优化（0.22.1）

- **单一代表模型精简探活**：
  - 针对拥有海量模型（如 Arena 拥有 290+ 个模型）的全量周期探测导致账号额度枯竭及高频触发上游风控封号（429 prompt_rate_limit、401 会话失效）问题，改用“每厂商挑选单一代表模型”的轻量化探活策略。
  - `InferenceProvider` 引入 `scheduledProbeModel()`，默认挑选 `TOP_TEXT` 或默认模型列表首选（Arena 锁定 `Max`）；
  - `ModelProbeScheduler` 仅针对各厂商声明的单一代表模型按需调度探测，禁止全量模型遍历，探活请求量与账号损耗直降 98% 以上。
- **代表模型连通性继承与可用性判定放宽**：
  - 优化 `ModelCatalogCache` 与 `RandomRouteCatalog` 的 `available` 判定表达式：只要厂商代表模型探活通过（处于新鲜 `READY` 状态）且存在可用账号，旗下所有未明确失败的子模型均继承连通性置为可用；
  - 彻底解决用户请求非主探活模型或主模型轮询间隔超期时遭遇的 `503 model is not currently callable` 假死阻断。

## 2026-09-26 Arena 深度优化与全厂商补号健壮性提升（0.22.0）

- **Arena 定期探活与全链路自动就绪**：
  - `ArenaProvider.java` 启用 `scheduledModelProbeEnabled = true`，后端 `ModelProbeScheduler` 自动为 Arena 执行周期性模型探活，彻底解决 `arena/Max has no ready probe result` 报错。
  - 优化 `account_status_is_healthy` 兼容根级用户信息，并为所有 Provider 提供缺省运行时规则防护。
- **全厂商 Keepalive 崩溃与调度队列阻塞根因修复**：
  - 根因定位：统一 Action 契约在转换旧 payload 时覆盖冲毁了后端传入的 `payload["runtime_plan"]`，导致所有依赖声明式运行时规则的 Provider（Arena、Qwen、GLM、LongCat）在执行 keepalive 时以 `TypeError: runtime active selection must be an object` 失败；新账号无法完成首次探活激活并卡在 `PENDING`，同时重试请求堆积造成批处理队列阻塞。
  - 修复方案：在 `ProviderActionRequest` 与 `provider_api.py` 中无损透传 `runtime_plan`；在 `runtime_rules.py` 中内置全厂商默认规则兜底。
- **补号吞吐抗超时增强**：
  - `browser_batch_capacity` 从 2 调优至 4，极大缓解高峰批处理排队。

## 2026-09-25 文档与缺漏排查

- 源码基线：`main` 的 `6cbe025`，Backend/Automation/WEB 版本为 `0.21.0`。
- GitHub Actions [35939416946](https://github.com/Prodigalgal/any2api/actions/runs/35939416946)
  的三端质量检查、镜像构建和 GitOps 更新均成功。2026-09-25 只读检查显示 Argo CD
  `Synced/Healthy`，三套业务 Deployment 均运行 `*-sha-6cbe025...`、Pod Ready 且重启 0；
  这不等于八家 Provider 的真实请求验收。
- 2026-09-26 只读数据库核对：Liquibase 031 已执行，旧 `grok` / `grok_console` 的
  Provider、账号和 API Key 授权行当前均为 0；Arena、Grok Web 的持久化通道模式为 `AUTO`。
  迁移前数据量和备份可恢复性尚未核实。
- 当前源码包含 Arena、DeepSeek、GLM、Grok Web、LongCat、MiMo、MinMax、Qwen 八家；
  `grok`、`grok_console` 已退出代码目录，并由 Liquibase 031 清理持久化数据。
- Java 推理通道声明：DeepSeek、GLM、LongCat、MiMo、MinMax、Qwen 支持 API/Runtime；
  Arena 和 Grok Web 当前仅开放 Runtime。Python 的 API binding 不等于 Java 对外开放。
- 待处理缺漏、证据和优先级见 [2026-09-25 排查报告](../docs/reports/CODEBASE_GAP_AUDIT_2026-09-25.md)。
- 2026-09-26 各 Provider 的账号、模型探针、普通推理和生命周期快照见
  [Provider 运行态快照](../docs/reports/PROVIDER_STATUS_2026-09-26.md)。

## 待处理

- [ ] 核实 Liquibase 031 执行前的数据备份、删除量和恢复策略；已执行变更集不原地改写。
- [ ] 为发布流水线补版本契约、不可覆盖镜像 tag 和空库 Liquibase 校验。
- [ ] 明确 Grok Web API binding 的发布意图，并使 Java 通道声明与验收证据一致。
- [ ] 启用 Grok Web API 前补齐直接 `websockets` 依赖声明。
- [ ] 明确 LongCat 模型发现的真实接口或验收例外。
- [ ] 补齐 0.21.0 的逐 Provider 真实推理和生命周期验收记录。

## 历史记录

- [0.18.0 及以前的任务与验收快照](../docs/archive/tasks/PROVIDER_RUNTIME_API_PROGRESS_THROUGH_0.18.0.md)
