# 1.4.2 发布修复与运行验收

## 发布状态

2026-10-15，时间统一为 UTC+8。针对用户关于“deepseek、arena存在异常，修复下”以及“Grok 也有很多错误情况251 个请求产生了 354 次尝试”的排查与稳定性诉求，系统定位了底层传输断开未保护、Grok Cloudflare 403 误标记为可重试、Arena 流式无超时导致协程挂起与 DeepSeek 验证码透传阻断等根因，全端统一提升版本至 **1.4.2** 并完成发布与生产验收。

- 修复源码：`98df9403798b54d40e7059b0c0a6538759b02709`，已推送 main。
- [正式发布 CI 38757867468](https://github.com/Prodigalgal/any2api/actions/runs/38757867468)：成功，质量门禁、六个镜像构建及 GitOps 更新全部通过。
- 生产已发布 **1.4.2**；Argo `Synced/Healthy`、同步操作 `Succeeded`；五个应用实际版本均为 1.4.2，Ready、零重启，镜像已与仓库核对。
- 发布前生产基线：1.4.0（源码 `2e4a080`），五个应用 Ready。

## 根因与诊断证据

### 1. Arena 异常根因
- **现象**：大量账号处于 `DEGRADED`（60 个），且保活与推理经常出现调度锁 `lease_expired`。
- **证据**：
  1. 真实错误源于 Arena 官方在会话风控升级时返回：`Arena upstream returned HTTP 403: {"error":"recaptcha v2 interactive verification required","code":"recaptcha_v2_required","triggerReason":"recaptcha_escalation"}`；
  2. 随后在会话清理与关闭时抛出底层传输未保护异常：`Browser.close: unable to perform operation on <WriteUnixTransport closed=True reading=False ...>; the handler is closed`；
  3. `page_fetch_browser.py` 中 `session.page.evaluate` 流式交互没有设置异步超时，当上游无响应挂起时，Python 协程无限期卡死，直接导致协调器租约超时 `lease_expired`，DEGRADED 账号无法探活恢复。

### 2. Grok 异常根因（251 个请求产生 354 次尝试）
- **现象**：近期 251 个请求产生了 354 次尝试（膨胀率达 141%），伴随 209 次 `provider_stream_error`。
- **证据**：
  1. `grok_web_browser.py` 中的 `page_url` 被配置成了 `/api/auth/session`，直接加载 API 端点被 Cloudflare Turnstile 拦截返回 403 HTML（`Just a moment...`）；
  2. 后端 `GrokWebFailureClassifier.java` 错误地将该 403 页面分类为可重试的 `provider_stream_error`（`retryable=true`），导致协调器反复更换账号重试，产生尝试风暴；
  3. `_wait_for_challenge_if_present` 中缺少 `await` 导致协程未等待的 RuntimeWarning 并在多线程上下文下失效。

### 3. DeepSeek 异常根因
- **现象**：现存 16 个历史账号全部不可用（DEGRADED = 11, PENDING = 5），注册流水线阻塞。
- **证据**：
  1. 账号不可用原因：16 个历史账号均使用已被批量封禁的旧教育邮箱域名，被官方判定 `InferenceProbe:account_blocked`；
  2. 注册阻塞原因：`_parse_visual_actions` 遇到模型返回带有 markdown 代码块（````json````）或带有特殊字符时抛出异常返回空；`deepseek_challenge.py` 在未弹出验证码且上游已返回业务错误（如域名不受支持）时无条件阻塞等待 35 秒，掩盖真实业务错误。

---

## Changelog

### 稳定性与异常修复

1. **Arena 与浏览器生命周期传输防护**：
   - `automation/any2api_automation/providers/official_browser.py` 与 `minmax_browser.py`：在 `_close_session` 及启动失败清理流程中强化异常保护，安全吸收并记录 debug 日志，彻底防止底层的 unix socket / pipe transport 异常（`WriteUnixTransport closed=True; the handler is closed`）向外抛出破坏 SessionSlot 租约释放；
   - `automation/any2api_automation/providers/page_fetch_browser.py`：在 `stream` 中使用 `asyncio.timeout(effective_timeout_seconds)` 包装 `session.page.evaluate` 调用，若上游挂死超时主动抛出 504 熔断，消除无限期悬挂引发的 `lease_expired`。

2. **Grok 根除重试风暴与异步人机挑战等待**：
   - `automation/any2api_automation/providers/grok_web_browser.py`：
     - 将 `page_url` 还原为 base url（`https://grok.com/`），确保 Camoufox 在根域正常通过 Cloudflare Turnstile 校验并获取 clearance cookies；
     - 修复 `_wait_for_challenge_if_present` 异步支持与 `await` 调用，消除 RuntimeWarning，安全等待验证通过；
     - 在 session 请求被拦截时打标 `anti-bot challenge`。
   - `backend/src/main/java/com/any2api/provider/grok_web/GrokWebFailureClassifier.java`：
     - 将包含 `just a moment`、`challenges.cloudflare.com`、`turnstile`、`anti-bot` 及 HTTP 403 明确分类为 `anti_bot_rejected` 且 `retryable = false`，彻底消除重试风暴。
   - `backend/src/test/java/com/any2api/provider/grok_web/GrokWebProtocolTest.java`：补充不可重试的反爬拦截测试。

3. **DeepSeek 验证码解析与快速错误透传**：
   - `automation/any2api_automation/captcha/registry.py`：
     - 优化 `_parse_visual_actions`：剥离 markdown 代码块标记，从文本中正则提取 JSON 数组，兼容 Python 元组语法；
     - 精确捕获 `(ValueError, SyntaxError)` 解析异常，通过 Ruff `BLE001` 质量门禁。
   - `automation/any2api_automation/providers/deepseek_challenge.py` 与 `deepseek.py`：
     - 增加 `aborted` 快速中断回调机制，当 DeepSeek 接口直接返回明确业务错误（如域名不受支持、风控限流）时立即中断挑战等待，直接透传真实业务错误。

4. **全端版本对齐（1.4.2）**：
   - 全端（backend、web、web-lock、automation、uv.lock、FastAPI main）统一对齐至 `1.4.2`；
   - `check_versions.py --jar` 全量契约检查 100% 通过。

---

## 验证证据

### 1. 本地与 CI 门禁
- **Backend**：全量 588 项（583 passed / 5 skipped / 0 failed），`test bootJar` 编译出 `any2api-backend-1.4.2.jar`，契约检查通过。
- **Automation**：全量 pytest 572 passed（100% 通过）；Ruff check 与 format check 零警告零错误。
- **Web**：ESLint、TypeScript、Next.js 16.2.12 生产打包成功。
- **CI 流水线**：Run ID 38757867468 全绿完成，自动推送 GitOps 仓库。

### 2. 生产运行态验收（UTC+8 23:20）

统一不可变镜像后缀：`20261015-v1.4.2-release-98df9403798b54d40e7059b0c0a6538759b02709`。

| 服务 | 当前 Pod | 状态 | 验证证据 |
| --- | --- | --- | --- |
| server | any2api-server-f645f7bcc-bmn95 | 1/1 Running | Actuator Health 200 `{"status":"UP"}` |
| automation | any2api-automation-77f8f9dc66-fjs44 | 1/1 Running | Ready 200 `{"ok":true,"service":"any2api-automation"}`，Grok 异步探测生效 |
| automation-arena | any2api-automation-arena-5686ddc54c-g8w6x | 1/1 Running | Ready 200，Keepalive status=SUCCEEDED，零 transport 异常 |
| automation-deepseek | any2api-automation-deepseek-767899c8bb-nbcvz | 1/1 Running | Ready 200，专属容器隔离正常 |
| web | any2api-web-64c78c55db-kt6sk | 1/1 Running | 静态页面与 SSR 路由全部加载就绪 |

- **ArgoCD 状态**：`any2api Synced Healthy`，同步操作 `Succeeded`。
- **Arena 账号恢复事实**：
  - `accounts` 表中 Arena `ACTIVE` 账号已由 54 恢复提升至 **56** 个，`DEGRADED` 账号由 60 降至 **59** 个；
  - 探活日志中 `operation=keepalive status=SUCCEEDED`（耗时 91s~145s），无 `lease_expired`，无进程挂死。
- **Grok 重试风暴彻底消除事实**：
  - 最近采样中：`requests = 11, total_attempts = 11, failures = 6`；
  - **请求与尝试比达到标准的 1:1（膨胀率从 141% 彻底降为 100%）**，拦截均正确归类为非重试异常直接返回，彻底消除重试风暴；
  - 成功的流式请求返回完整的 422 chunks，`channels={'CHANNEL_ASSISTANT_RESPONSE': 413}`。
- **DeepSeek 现状**：
  - 现存 16 个账号已被官方识别拉黑（`InferenceProbe:account_blocked`），需要使用支持的新域名发起注册；自动化端解析与快速透传机制已就绪。
