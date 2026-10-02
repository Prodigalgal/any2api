# OpenAI Chat/Responses 到厂商 WEB 的桥接

- 当前发布：0.24.4 / `b81886c`；分支：`codex/openai-agent-protocol`；起点：`1fed4b7`（0.23.4）。0.24.0 是协议结构与迁移的历史引入版本。
- 授权范围：推进到阶段 4、提交部署和真实测试；沿用 Provider/Action/Channel。最新范围为 OpenAI Chat/Responses 常用接口到厂商 WEB；工具执行属于调用方，namespace/custom 不作为全部厂商必需能力。
- 阶段 1–3：已实现并本地验证。
- 阶段 4 发布：CI `37020984892` success、GitOps `49b70b3` Synced/Healthy、四应用 Ready/restart 0；source/installed/API 版本一致。
- 阶段 4 核心验收：最终 MiMo/LongCat SDK 7 组均通过，MiMo 标准 Responses 工具图片结果通过；Grok 普通 Responses/SSE/function 续接前三组通过。Backend 379 passed / 5 skipped，Automation 486 passed，Web lint/build 与版本门禁通过。
- 后续常用 WEB 桥接：Grok 独立 Chat required function 120s 客户端超时、LongCat 工具图片及输入错误熔断隔离、实际使用厂商的 function/图片成功与拒绝、公网 API 错误透传；Grok namespace 空输出保留扩展限制。其他厂商组合、长对话/并行压力与长期稳定性未完成本轮验证。
- 后续 Read 性能：已批量修复 Key/规则/厂商列表与 Netty 阻塞；继续定位 DB 网络、查询往返、借连接校验与目录冷 SQL，不扩大协议范围。
- 契约：[需求规格](../../docs/requirements/OPENAI_AGENT_COMPATIBILITY.md)。
- 当前证据与 changelog：[发布验收与性能](../../docs/reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)；[0.24.0 本地验收](../../docs/reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)为历史记录。
- 运行方式：[OpenAI API 桥接](../../docs/integrations/OPENAI_AGENTS.md)；Codex 实验可选，不承接客户端模型目录和本机 shell/patch 权限。
- 发布/性能范围：[执行规格](../../docs/requirements/RELEASE_AND_READ_PERFORMANCE.md)。最终范围、报告和任务板按 docs-only 提交，不重建已验收 0.24.4 制品。
- 回滚：上一不可变 Backend/Web/Automation 制品；032 的 additive schema 可保留，需完全撤销时先停写和备份，再撤销 tag 与 032。不覆盖已发布版本或镜像。
