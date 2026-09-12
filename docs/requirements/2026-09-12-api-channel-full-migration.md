# 全厂商 API Channel 迁移任务规格

## 目标

为已纳入当前业务范围的 Arena、DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 建立真实的
API Channel：Java 只负责统一语义、模式选择、账号租约和 canonical event；Python provider
负责厂商 Web/CLI API 的路径、请求体、签名、SSE、错误和文档/图片上传。Grok 系列不在本任务范围。

## 范围

- 统一 `API` 与 `camoufox_browser_runtime` 的 Action binding 和能力校验。
- API 模式覆盖模型发现、文本非流式、文本 SSE，以及厂商已具备真实协议证据的图片/PDF 输入。
- API 模式复用加密凭证、代理租约、账号隔离和 credential patch；不复用浏览器进程、storage state、
  fingerprint 或 Runtime canary 状态。
- Java 的 chat 和 model discovery 必须把最终 `ProviderTransportMode` 传到共享 Action client。
- API 失败时由 `AUTO` 按既有策略回退 Runtime；显式 `API` 不静默回退。
- 每个厂商独立实现 mapper、API client、SSE decoder、媒体上传和错误分类；公共层只提供有界 HTTP、
  代理租约、响应帧、取消语义和受限的 `Set-Cookie` credential patch 传播。

## 非目标

- 不实现或增强 CAPTCHA、风控、限额、权限和协议限制的绕过。
- 不把任意 URL、任意 JavaScript 或用户原始请求体转发到上游。
- 不把生命周期注册、重新认证、保活、打卡强行迁移到 API；这些动作仍由 Runtime 承载。
- 不宣称某个厂商 API 已就绪，除非完成固定夹具、单测和真实 K8S 非流式/SSE 验收。

## 影响文件

- `automation/any2api_automation/providers/actions.py`
- `automation/any2api_automation/providers/channels.py`
- `automation/any2api_automation/providers/*_api_actions.py`
- `backend/src/main/java/com/any2api/provider/InferenceProvider.java`
- `backend/src/main/java/com/any2api/provider/ProviderCatalogSynchronizer.java`
- 目标厂商 Java provider 与 API/Runtime 模式测试
- `automation/pyproject.toml`、`backend/build.gradle.kts`、`web/package.json` 及对应锁文件/发布记录

## 验收标准

1. manifest 声明 API 时，API 的每个声明 inference action 都有独立 binding；生命周期 action 不得出现 API binding。
2. 显式 `API` 的请求体、路径和上游响应不经过浏览器 session；`AUTO` 只在分类为可回退的 API 失败时选择 Runtime。
3. 目标厂商各自通过模型发现、文本 non-stream、文本 SSE；支持文档/图片的厂商再通过对应媒体用例。
4. 请求取消、响应上限、超时、401/403、429、5xx、无效 JSON/SSE、credential patch 均有测试。
5. GitHub Actions `build-and-deploy.yml` 的 `backend-quality`、`automation-quality`、
   `web-quality` 三个 Job 全部通过；本地只执行不产生构建制品的 `git diff --check`。
6. K8S 真实请求逐厂商记录 `API` 与 `AUTO` 的模式证据；未通过的厂商保持 Runtime-only，不伪造就绪状态。

## 实施顺序

先完成公共 binding/模式传递和 API HTTP 基础层，再按 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen、Arena
逐个接入；每个 adapter 完成夹具和真实 smoke 后才将该厂商的 manifest/API 模式标记为可用。
