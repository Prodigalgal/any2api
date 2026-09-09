# 已归档文档

这里保存已被现行架构决策取代、但仍需保留历史上下文的文档。归档内容不作为当前实现规范。

当前统一方案以 [ADR-0007](../adr/0007-action-channel-boundaries.md) 为准；Runtime 默认和
历史迁移背景仍见 [ADR-0006](../adr/0006-unified-camoufox-inference-runtime.md)。

| 归档内容 | 取代原因 |
|---|---|
| `adr/0003-provider-plugin-boundaries.md` | 原物理传输职责边界由 Java 持有上游协议，已改为 Python Camoufox Runtime |
| `adr/0005-official-browser-runtime-transport.md` | 原仅部分 provider 使用 Runtime，已改为全 provider 统一顶层 Runtime |
| `requirements/PROVIDER_OFFICIAL_BROWSER_TRANSPORT.md` | 原“稳定 API 保持 Java native”的范围已取消 |
| `requirements/PROVIDER_RUNTIME_HOT_UPDATE.md` | 原仅覆盖 MiMo/GLM 的热更新范围已扩展为分阶段全 provider 迁移 |
