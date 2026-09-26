# 当前任务板（源码 0.22.0）

> 当前源码事实以代码和 [API 契约](../docs/architecture/API_CONTRACTS.md)为准；
> 历史任务与运行态快照已归档，不作为 0.21.0 的生产就绪结论。

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
