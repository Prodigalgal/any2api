# OpenAI agent 协议升级

- 版本：0.24.0；分支：`codex/openai-agent-protocol`；起点：`1fed4b7`（0.23.4）。
- 授权范围：按用户指示逐步推进到阶段 4；沿用既有 Provider/Action/Channel 架构。
- 阶段 1–3：已实现并本地验证。
- 阶段 4：本地候选、官方 SDK 和真实 Codex CLI 验收通过；用户已授权提交部署及真实环境测试，正在推进。
- 契约：[需求规格](../../docs/requirements/OPENAI_AGENT_COMPATIBILITY.md)。
- 证据与 changelog：[验收记录](../../docs/reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)。
- 运行方式：[客户端接入](../../docs/integrations/OPENAI_AGENTS.md)。
- 发布候选：0.24.1（0.24.0 本地验收后补齐发布配置）；范围见 [发布与性能规格](../../docs/requirements/RELEASE_AND_READ_PERFORMANCE.md)。执行实际厂商工具循环，并记录不可变镜像、GitOps/Pod、API 和 Read 性能证据。
- 回滚：上一不可变 Backend/Web/Automation 制品；032 的 additive schema 可保留，需完全撤销时先停写和备份，再撤销 tag 与 032。不覆盖已发布版本或镜像。
