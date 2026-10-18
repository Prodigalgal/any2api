# 1.5.4 Arena 请求兼容、v2 iframe 与取消边界修复

本文时间为 UTC+8，区分官方浏览器对照、桥接契约、部署健康与真实输出。1.5.3 源码 `4299f4c` 的三项 CI 门禁通过；发布前发现 v2 iframe 选择错误，已取消 [CI 39121533392](https://github.com/Prodigalgal/any2api/actions/runs/39121533392) 的 GitOps 发布，版本与已生成制品保留占用。1.5.4 已完成部署验收，Arena 真实输出未恢复。

## 已定位的事实

- 10 月 16 日 Max 尚有 26 条成功普通推理，最后成功请求为 `a41423ca-f451-4602-862e-fd95433eb6f7`，开始于 18:12:12、耗时约 104 秒。17、18 日的历史查询中没有 Max 成功记录。
- 最近 24 小时查询到的 346 条验证码失败，均记录 v3 校验失败后的 `recaptcha_escalation` / `recaptcha_v2_required`，不是 `prompt_rate_limit` 被误判为验证码。
- 选择上述最后成功账号，恢复其浏览器状态与凭据，分别通过新固定 IPv6、原 Self-hosted Oracle 执行官方原生页面的 headless 自动化提交。两次均已登录（`/api/me` 为 200），v3 Token 可取得、action 为 `chat_submit`，但生成提交返回 403 `recaptcha validation failed` 并出现验证对话框。
- 上述对照不能证明所有账号、真实人工浏览器或全部 IP 均被阻断，也无法取得 Google 服务端的风险评分；不能据此确定 IP 封禁或平台策略变更。
- 另用相同账号、新固定 IPv6 启动 Xvfb + headful 浏览器，官方提交也返回相同 403/v2；累计三次定向原生提交，均无有效输出，诊断 Pod 已清理。仅关闭 headless 未解决当前账号/环境的验证问题。
- 不发送上游生成的控件对照中，新选择器已真实点击 checkbox；随后 Google bframe 扩展为 400×580 的图形验证，等待 10 秒仍未勾选、v2 Token 长度为 0。控件加载/选择已打通，但当前账号/环境的交互验证尚未完成。
- 当前官方请求使用页面自身生成的 20 字符 `cx`。旧桥接漏掉此字段；官方同源 fetch 会为绝对 URL 自动补齐 `x-arena-web-deployment`、`x-deployment-id`，部署头没有证据表明缺失。

## Changelog

### 请求兼容：`arena_browser.py`

- 沿用现有 Turbopack 模块发现逻辑，读取当前页面导出的 `buildProgrammaticClientInteraction` / `packClientInteraction`，由官方函数生成真实 programmatic 上下文，随每次提交加入 `cx`。不复制当前部署盐值、不硬编码模块 ID 或压缩格式、不伪造可信事件。
- 官方模块尚未暴露交互函数时保留旧请求的兼容路径；已有上传导出、附件签名上传和模型解析保持。
- v3 Token 不可用或 ready/execute 失败时返回明确的 `recaptcha_v3_unavailable`，不继续发送缺 Token 的生成请求；Token 不缓存、不复用。

### 取消与回收：`arena_browser.py` / `page_fetch_browser.py`

- v2 辅助任务改为选择可见的 anchor iframe，再限定单个 frame。真实页面同时有 0×0 的 v3 anchor 与 304×78 的 v2 anchor，旧选择器读取 checkbox 为 0，新选择器为 1 且可见；这解释辅助任务此前未进入点击步骤，并非 v2 API 不存在或脚本 key 失效。
- v2 等待响应本次请求的 AbortController deadline，保留原 403/429 与 `recaptcha_v2_required`，避免等待状态脱离超时预算后变成泛化 504。
- 验证结束时释放 timeout、abort listener、widget 与容器。移除重复且不能访问跨域 checkbox 的页面 JS 点击循环，保留 Playwright 的 iframe checkbox 路径。
- 同一流最多保留一个进行中的验证辅助任务；结束/取消时取消并等待该任务，避免继续操作已释放的会话。
- 增加默认无动作的 provider 取消 hook；Arena 用 request_id 找到页面内 AbortController，显式中止上游工作。清理 hook 最多等待 2 秒，失败记录错误类型后继续释放资源，不改变其他厂商请求脚本。

### 清理无用调试状态与版本

- 删除没有消费者的 `_arena_fetch_interceptor_script` 和 `__any2apiArenaFetchLog`，避免额外包装 fetch、无界保存正文、附件元数据及验证码 Token。
- Backend、Web、Automation、锁文件与运行版本统一为 1.5.4；无数据库迁移，无下游 OpenAI 协议变化。IPv6 服务与厂家绑定保持，DeepSeek/Qwen 继续停用。

## 本地及页面验证

| 验证 | 结果及边界 |
| --- | --- |
| Automation 全量 | 1.5.4 共 613 passed，包含 Redis 集成及隐藏/可见 iframe 共存回归；1 条既有 Starlette/httpx 弃用警告 |
| JS 行为 | 正常流、官方交互/上传导出共存、v3 缺失/ready 异常/截止、v2 超时/截止/取消/已解决后只重试一次、定时器/widget/controller 回收 |
| Runtime 异步 | 正常结束与主动取消均回收唯一辅助任务、清空请求队列；取消调用页面终止 hook |
| Backend | `test` 缓存有效，原 601 项中 596 通过/5 条需显式开启的 Grok 在线测试跳过；1.5.4 `bootJar` 重建成功，后续 CI 重跑质量门禁 |
| Web / Ruff | lint、生产构建、TypeScript、Ruff check、135 文件 format check 通过 |
| 版本 | 1.5.4 源码与 `any2api-backend-1.5.4.jar` 契约通过 |
| 当前官方页面 | 新脚本取得页面函数，生成 20 字符 `cx`；拦截桥接请求，确认 v3 Token 和双部署头；真实上游生成提交为 0 次 |

官方页面拦截和本地合成流不是推理恢复证据；多模态生成和工具输出的线上验收仍受验证码阻断。

## 部署与真实业务验收

- 源码 `68d81e4ef343bb01b9134786a672b51240646c0d`，[CI 39122208522](https://github.com/Prodigalgal/any2api/actions/runs/39122208522) 成功，GitOps `9239a2661b8325d67c1df6ddd157d02f17d117f9`；19:20 左右四应用完成滚动，Argo Synced/Healthy。
- Backend、Web、共享 Automation 与 Arena Automation 实际版本均为 1.5.4，运行镜像为 `*-20261018-v1.5.4-release-68d81e4ef343bb01b9134786a672b51240646c0d`；四个运行 digest 与 Registry arm64 制品逐一相符。DeepSeek/Qwen 继续停用、零副本。
- 双公网 health/ready 为 200，Chat/Responses 缺少鉴权时返回带 request_id 的 OpenAI 401，六家独立 Key 的模型目录均 200；未发送生成探活。
- 发布验收时四应用零重启。后续共享 Automation 于 19:51:19 以 143 退出一次，22:06 复查已 Running/Ready、累计 1 次重启，其余应用零重启；没有 OOM 证据。
- 21:45 附近最近 30 分钟 Arena 5 次请求均 TTFB=0、约 300 秒后下游取消。细查账号获取约 100 毫秒，Automation HTTP 入口已收到请求，不能归为“没到自动化服务”；准备阶段缺少日志且已有异步关闭无超时缺口，转入 [1.5.5 修复](../requirements/ARENA_SESSION_RECOVERY_1.5.5.md)。
- 用户选择复用已有通过验证的会话。盘点 40 个有历史成功的启用账号均有保存状态，没有发现独立的当前已验证会话文件。选另一 ACTIVE 账号恢复完整状态并使用原 Self Proxy 出口，官方原生提交一次“你好”：登录有效、v3 Token 可获取，生成仍 403 `recaptcha validation failed` 并出现 v2。历史成功状态不能证明当前能生成，也不支持扩大到所有账号的结论。

## 回滚与遗留

- 回滚使用 1.5.2 的 Backend/Web/共享 Automation/Arena Automation 不可变镜像组合；无数据迁移，不需要回滚账号或代理数据。
- 1.5.2 的进程身份保护、隧道复用、HTTP 代理认证与固定 IPv6 服务保持；本次不回滚到缺少认证映射的 1.5.1。
- 若相同账号在正常人工浏览器也要求 v2，则需要完成官方验证后在相同会话/出口内再测；不能通过补字段宣称消除官方交互门槛。不恢复定时生成探活或批量轮换消耗请求。
