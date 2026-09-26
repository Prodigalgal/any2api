# 文档入口

本页区分源码契约、历史决策和带日期的运行态证据。仓库中的 `0.21.0` 版本号只说明
当前 checkout 的源码版本；CI 通过、镜像发布、GitOps 同步及生产就绪需要各自核验。

## 当前源码与开发

- [项目概览](../README.md)：应用组成、开发入口、当前 Provider 范围。
- [架构](architecture/ARCHITECTURE.md)与[模块职责](architecture/MODULES.md)：部署边界和代码所有权。
- [API 与事件契约](architecture/API_CONTRACTS.md)：公开路由、通道选择、参数和 Provider 能力。
- [Provider 扩展](architecture/PROVIDER_EXTENSION.md)：新增或修改 Provider 的边界。
- [开发指南](DEVELOPMENT.md)：本地命令、数据库迁移和验证要求。
- [当前任务板](../tasks/current.md)：当前源码基线、只读核验结果和待处理事项；旧版本记录已归档。

## 决策、验收与排查

- [ADR-0007](adr/0007-action-channel-boundaries.md) 和
  [ADR-0009](adr/0009-provider-api-channel-rollout.md)记录 Action/Channel 决策及当时的 rollout 范围；
  当前可选通道以 [API 契约中的 0.21.0 矩阵](architecture/API_CONTRACTS.md)为准。
- [2026-09-25 缺漏排查](reports/CODEBASE_GAP_AUDIT_2026-09-25.md)列出代码及发布门禁缺口。
- [2026-09-26 Provider 运行态快照](reports/PROVIDER_STATUS_2026-09-26.md)区分普通推理、自动探针、
  账号状态和生命周期失败。
- `reports/` 中的真实请求和集群数据只对应文件日期、版本与环境，不能直接推断今天的生产状态。
- `requirements/` 保存任务规格；[全链路验收规格](requirements/FULLCHAIN_PROVIDER_ACCEPTANCE.md)
  内的账号数和阻断点是 2026-09-13 至 2026-09-20 的快照。
- [已归档文档](archive/README.md)保留被取代的方案，包括退役的 Grok Build/Console 三通道资料。
