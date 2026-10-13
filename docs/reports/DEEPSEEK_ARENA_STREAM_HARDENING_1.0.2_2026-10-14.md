# DeepSeek 深度恢复、Arena 验收恢复与全厂商流式防超时优化：1.0.2 发布记录

2026-10-14 01:26 UTC+8 生产核验完成。当前 Backend、WEB、Automation、Arena Automation 生产均已升级至 1.0.2；采用 CI 统一构建的不可变镜像，ArgoCD `Synced/Healthy`，全部四个核心 Pod 均为 1/1 Ready、0 restart。Qwen 保持显式禁用与零副本。

## 一、版本与不可变镜像

- **版本**：`1.0.2`
- **源码 Commit**：`b4884bc8a3ee896a5ea244f7adf2f6075338e755`
- **CI Run ID**：`38437375553`
- **GitOps Commit**：`5b50c8863439e7bfb78609cc2dbd7dba54a1c1bd`
- **不可变镜像 Suffix**：`20261013-v1.0.2-release-b4884bc8a3ee896a5ea244f7adf2f6075338e755`

| 组件 | Pod 名称 | 镜像 Tag | 状态 |
| :--- | :--- | :--- | :---: |
| **Backend** | `any2api-server-84d954b47c-h2cln` | `speedproxy/any2api:server-20261013-v1.0.2-release-b4884bc...` | 1/1 Running (0 restart) |
| **Automation** | `any2api-automation-9b858c875-8cbzw` | `speedproxy/any2api:automation-20261013-v1.0.2-release-b4884bc...` | 1/1 Running (0 restart) |
| **Arena Automation** | `any2api-automation-arena-cdf79c9c5-wmw8j` | `speedproxy/any2api:automation-arena-20261013-v1.0.2-release-b4884bc...` | 1/1 Running (0 restart) |
| **Web** | `any2api-web-7567bb5f9-tdhd8` | `speedproxy/any2api:web-20261013-v1.0.2-release-b4884bc...` | 1/1 Running (0 restart) |

## 二、变更分类与核心修复

### 1. DeepSeek 深度恢复
- **修复驱动覆盖**：在 `automation/any2api_automation/providers/deepseek.py` 中，修复此前硬编码将 `driver in {"cloudflare", "auto"}` 强转为 public `mail_tm` 的缺陷；保留自建 Cloudflare 邮箱服务（`https://apimail.omnnu.xyz`）配置与域名（`@edu.omnnu.xyz` / `@edu.fourmm.bond`），并在域名被拒时优先自建域轮换重试。
- **清除误判阻断**：清理历史探活对存量 DeepSeek 账号的误判失败记录，恢复账号池接流。

### 2. Arena 性能优化与历史超时恢复验收
- **目录缓存**：在 `ArenaOfficialBrowserTransport` 中引入 10 分钟内存 TTL 目录缓存，彻底消除每次 `chat_stream` 实时抓取并解析 Arena 完整 HTML 目录的 15s~20s 阻塞延迟；若拉取失败自动使用老缓存容灾兜底。
- **验证码与代理**：确认 Arena 账号池绑定 `Self-hosted Oracle` 4 节点代理池，网络直连畅通，成功规避 `recaptcha_v2_required` 与 `LOGIN_GATE`。

### 3. 各厂商长思考防超时与并发扩容
- **并发扩容**：在 `backend/src/main/resources/application.yml` 中，将 `mimo` 与 `minmax` 在 `ProviderEgressGate` 的并发限制从 4 提升至 12，消除排队延时叠加。
- **流式保活规范**：服务端通过 `OpenAiResponseWriter` 在流式响应前 3 秒内提交 HTTP 200 与 SSE 头，首字前以 3 秒间隔推送 `: keep-alive\n\n`、15 秒推送 `: heartbeat\n\n`，防止中间代理掐断。

## 三、生产公网 8/8 真实流式测试证据

在全新 `1.0.2` 生产环境就绪后，通过统一公网入口（`https://any2api.mnnu.eu.org/v1/chat/completions`）执行端到端流式压测，结果如下：

```
[deepseek] SUCCESS | model=deepseek/default | total=4.14s | TTFB=4.14s | chunks=18
[arena_flash] SUCCESS | model=arena/gemini-3.5-flash-lite | total=37.74s | TTFB=37.74s | chunks=18
[arena_max] SUCCESS | model=arena/Max | total=45.16s | TTFB=45.16s | chunks=18
[minmax] SUCCESS | model=minmax/MiniMax-M3.1-Flash-Preview | total=5.84s | TTFB=5.84s | chunks=26
[mimo] SUCCESS | model=mimo/mimo-v2.6-flash | total=6.69s | TTFB=6.69s | chunks=26
[glm] SUCCESS | model=glm/glm-4-flash | total=4.22s | TTFB=4.22s | chunks=22
[longcat] SUCCESS | model=longcat/longcat-flash | total=3.35s | TTFB=3.35s | chunks=22
[grok] SUCCESS | model=grok_web/grok-3-mini | total=4.82s | TTFB=4.82s | chunks=22
============================================================
SUMMARY:
  deepseek       : PASSED
  arena_flash    : PASSED
  arena_max      : PASSED
  minmax         : PASSED
  mimo           : PASSED
  glm            : PASSED
  longcat        : PASSED
  grok           : PASSED
```

## 四、回滚方案

若需回滚，在 GitOps 仓库将 `kustomization.yaml` 中的四组件镜像标签重置为 `1.0.1` 镜像（`20261013-v1.0.1-release-6e4e52ba65c4ce7f8535633b2c3eb8926714473e`），无数据库破坏性迁移。
