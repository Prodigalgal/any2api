# 已归档文档

这里保存已被现行架构决策取代、但仍需保留历史上下文的文档。归档内容不作为当前实现规范。

当前统一方案以 [ADR-0007](../adr/0007-action-channel-boundaries.md) 为准；Runtime 默认和
历史迁移背景仍见 [ADR-0006](../adr/0006-unified-camoufox-inference-runtime.md)。

| 归档内容 | 取代原因 |
|---|---|
| `adr/0003-provider-plugin-boundaries.md` | 原由 Java 持有的上游协议执行已迁至 Python Provider Action binding |
| `adr/0005-official-browser-runtime-transport.md` | 原仅部分 Provider 使用 Runtime；现由统一 Action/Channel 边界支持 Runtime 和已声明的 API binding |
| `requirements/PROVIDER_OFFICIAL_BROWSER_TRANSPORT.md` | 原“稳定 API 保持 Java native”的范围已取消，厂商 API binding 位于 Python |
| `requirements/PROVIDER_RUNTIME_HOT_UPDATE.md` | 原仅覆盖 MiMo/GLM 的热更新范围已扩展为分阶段全 provider 迁移 |
| `architecture/GROK_CHANNEL_PARITY.md` | 0.21.0 退役 `grok`、`grok_console` 后，三通道对齐合同只保留历史参考 |
| `architecture/GROK_REGISTRATION_FLOW.md` | 旧 Grok Build/Console 注册、授权与恢复流程不再是现行生命周期契约 |
| `requirements/GROK_ACTIVATION_AND_PROVIDER_E2E.md` | 原 Grok Build/Web/Console 激活目标已被 0.21.0 的 `grok_web` 单通道取代 |
| `tasks/PROVIDER_RUNTIME_API_PROGRESS_THROUGH_0.18.0.md` | 旧 `tasks/current.md` 的 0.18.0 及更早任务和运行态快照，已从当前任务板移出 |
