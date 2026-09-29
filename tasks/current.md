# 当前任务板（源码 0.22.2）

> 当前源码事实以代码和 [API 契约](../docs/architecture/API_CONTRACTS.md)为准；
> 历史任务与运行态快照已归档。

## 2026-09-29 探活鲁棒性增强与账号保活死循环治理（0.22.2）

- **探活账号并发争抢误杀根除**：
  - `ModelProbeService` 中捕获 `AccountUnavailableException` 与 `AccountCapacityException`（无空闲账号/并发占满），直接跳过持久化而不记录为 `FAILED`，杜绝因线上业务请求占用账号导致代表探针被误杀覆盖，防止旗下子模型发生雪崩式 503。
- **探针时钟抖动采样断档根除（半衰期预刷新 + 连通性继承窗口放宽）**：
  - `ModelProbeScheduler` 将检查阈值从 `freshness` 前置为 `freshness.dividedBy(2)`（15 分钟），实现主动预刷新（Prefetch），保证探针在 15~30 分钟生命周期内平滑轮转；
  - `ModelCatalogCache` 与 `RandomRouteCatalog` 将代表探针继承窗口放宽至 2 小时（`providerProbeFreshAfter`），彻底消除调度周期采样交错带来的短暂停摆断档。
- **Arena 激活超时治理**：
  - 将 `arena_recaptcha_v2_timeout_seconds` 默认值从 180 秒缩短为 10 秒，在无头环境中遇到 v2 挑战时快速返回明确失败，杜绝阻塞 180 秒导致后端 120 秒抛出 `TimeoutException` 和 `provider_transport_error`。
- **Keepalive 失败死循环与 Camoufox 内存暴涨治理**：
  - `LifecycleScheduler` 中当 keepalive 任务耗尽 `MAX_ATTEMPTS` 时，将该账号置为 `DEGRADED, enabled = false`，阻止 `reactivateExhaustedActions` 周期性无脑复活死账号，彻底根除高频无效拉起 Camoufox 导致的自动化 Pod OOM / SIGABRT 134 崩溃。

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
