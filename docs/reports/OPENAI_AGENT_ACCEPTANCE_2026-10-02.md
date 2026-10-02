# OpenAI agent 升级与验收记录（2026-10-02）

## 交付状态

- 源码版本：**0.24.0**；基线：`1fed4b7f1886865f7d5aeb8a3039a7efbaf92b22`（0.23.4）。
- 分支：`codex/openai-agent-protocol`；变更保留在本地工作树，未提交、推送或部署。
- 阶段 1–3 已实现；阶段 4 的本地候选、SDK/Codex 互操作和数据库验收通过。
- 真实厂商推理、测试环境部署、不可变镜像发布、GitOps/Pod 与生产状态未验证。环境/凭据/外部写入授权按 AGENTS.md Stop Conditions 等待确认。

## 详细 changelog

| 分类 | 关键文件 / 模块 | 行为变化与兼容性 |
|---|---|---|
| 协议输入 | `CanonicalRequestParser`、`OpenAiToolBridge` | 保留 reasoning/phase 原始历史；回放 refusal、并行 function/custom 调用和媒体结果。namespace/custom 转现有 function 协议，输出恢复原身份；校验 allowed_tools、格式和重复名称 |
| 参数与能力 | `ProviderRequestValidation`、`ModelCapabilityContract`、`GrokWebToolProtocol` | 接受网关字段、verbosity 和 summary；公开资源能力及模拟工具限制；拒绝 emulated strict、grammar/deferred、background 和未实现的 enrichment；Grok strict 不再无声忽略 |
| 输出与错误 | `OpenAiResponseWriter`、`CanonicalEvent`、`ApiExceptionHandler` | 输出索引/ID/时间一致；补全 completion-only 和部分工具参数；不一致参数失败；区分 completed/incomplete/failed；开流前使用 HTTP JSON 错误，失败保留部分输出 |
| 上下文与缓存 | `SmartContextWindowManager`、`PromptExactCacheManager` | Responses 裁剪不插入 Chat fields；保留 developer/system 和跨 commentary 的完整调用组；disabled 超限拒绝。缓存按 Key/协议隔离、恢复新的 started/ID；工具/状态/推理/结构化请求绕过文本缓存 |
| 公共状态 | `ResponsesService`、`GatewayResponseStore`、`ResponsesResourceController` | `store:true`、续接、retrieve/delete/input_items；API Key 归属与当前权限复核；TTL、单条大小和并发配额；固定模型续接及随机资源路由；禁止删除后被终态重新插入 |
| 原生状态与编排 | `ProviderExecutionContext`、`InferenceCoordinator`、`ProviderResponseStateStore`、`GrokWebProvider` | 传递 Key UUID；Grok 原生状态校验归属/模型，完成前持久化；账号亲和保留。随机首帧失败可以切换，开流后的错误不更换 Provider；既有随机 AUTO 通道策略保留 |
| 数据与配置 | Liquibase `032`、`Any2ApiProperties`、`application.yml`、retention scheduler | 新增 `gateway_responses` 和原生 state Key FK；真实 tag `0.24.0`；24h / 2MiB / 1000 条默认限制及参数校验，短事务锁沿用 `PostgresAdvisoryLocks`，批量清理过期记录 |
| 测试和交付 | `AgentProtocolContractTest`、`ResponsesStateIntegrationTest`、Controller/cache/auth tests、`AgentInteropServer`、`tools/compatibility/*` | 真实 PostgreSQL 全迁移、配额并发、权限收回、取消/异常/删除竞态及 rollback；官方 SDK/Codex 脚本和版本检查；CI 自动执行版本门禁 |
| 版本与文档 | Backend/Web/Automation 配置和 lockfile，API 契约、接入指南、任务/spec | 统一 0.24.0；只扩展协议能力，管理 UI 行为和 Provider 既有通道边界保持当前契约；历史任务记录保留 |

### 数据与 API 兼容

迁移 032 是 additive schema：新增公共资源表，并为已有原生状态增加 nullable `api_key_id`。现有表和数据不删除。旧无归属 Grok state 对分发 Key 拒绝访问，配置的全权限系统入口仍使用 `system` 归属。

`store` 缺省 false；公共资源仅在 `store:true` 创建。Provider 原生 state 和上游历史继续按各自生命周期处理。新增资源和历史回放扩展为 minor 版本；`strict:true`/超限/未支持字段会明确失败，不宣称模拟能力具有 OpenAI 原生保证。

文本缓存 key 版本升级，旧的不完整条目不再命中；新条目有 fresh response ID 和 Key 隔离。默认上下文管理属于消息裁剪，完整工具组可略超 32 条目标，不是语义 compaction。

## 验证结果

| 验证 | 结果 | 证据边界 |
|---|---|---|
| Backend `test bootJar --rerun-tasks` | **376 项：371 passed、5 skipped、0 failures/errors** | 包含真实 PostgreSQL；`GrokWebStatsigLiveInteropTest` 的 5 项 live 条件缺失而跳过 |
| Automation `pytest` | **482 passed** | 既有测试全量通过；1 个现有 Starlette deprecation warning |
| Automation ruff / uv lock | changed file check/format 通过；`uv lock --check --offline` 通过 | 版本和 lockfile 一致 |
| Web | `npm run lint`、`npm run build` 通过 | TypeScript 和生产构建；本轮 UI 仅版本变动 |
| 客户端脚本 | ruff check/format、Python compile 通过 | 测试 helper；无生产依赖注入 |
| 版本契约 | 源码、package/lock、FastAPI、迁移 tag、JAR manifest 均为 0.24.0 | CI 增加该校验；镜像/运行态尚未产生 |
| 官方 SDK | **OpenAI Python 2.54.0，9 组 checks 通过** | 真实 HTTP 服务，受控上游生成 |
| Codex CLI | **0.159.2，read-only view_image 闭环通过，exit 0** | 真实工具执行/结果回传和后续图片输入；受控上游生成，非真实模型决策 |
| Liquibase | 全链迁移、tag、rollback 新增 2 changesets、重新 update 通过 | PostgreSQL 14.22；rollback 保留既有 Provider schema |

SDK 覆盖：Responses JSON/client fields；SSE 累积和增量到达；stored 工具循环、查询、分页、删除及跨 Key 404；namespace 身份；custom 回放与 custom SSE；Chat 工具循环、stream/usage；并行调用及多结果；超过 32 条历史、incomplete、strict 拒绝、首帧限流/非法模型错误。

最后一次受控流：first delta 约 `0.188s`、整体约 `0.396s`。fixture 对事件施加固定间隔，此处只证明数据在终态前到达，不是厂商延迟指标。

Codex 两次 HTTP 请求完成 `function_call -> view_image -> function_call_output -> 最终回答`，后续请求带 1 个图片输入。关闭 apps/plugins/multi_agent；不修改用户全局 Codex 配置。自定义模型路由触发非阻断的 model metadata fallback 提示。PowerShell 命令执行被本机 CLI policy 拒绝，因此 shell/patch 不能算已验收。

## 本地制品

- 原始 JAR：`backend/build/libs/any2api-backend-0.24.0.jar`。
- 快照：`backend/build/candidates/20261002-v0.24.0-agent-acceptance-e51bea47d4c8.jar`。
- SHA256：`e51bea47d4c830ba4931f5ffafa98dfd81cb8e4cfd3bbf788d26300556b84991`。
- 记录：`backend/build/candidates/agent-acceptance.json`；SDK/Codex 报告在 `backend/build/agent-*-report.json`。这些构建产物被 Git 忽略。

该快照只对应本地候选。Backend 构建成功不代表镜像已发布、GitOps 已同步、Pod 健康或真实厂商认证 E2E 已通过。生产版本与健康状态本次未查询。

## 遗留风险与下一步

1. 在用户授权的测试环境部署候选，使用授权 Key 分别验证 MiMo、LongCat、Grok Web 的真实工具选择、结果理解、失败与长对话；账号可用/普通文本能回答不等同于 agent 验收。
2. 实际 Codex 客户端的 shell/patch 执行策略、model metadata、插件工具数量和媒体权限需要按使用配置验证。函数桥接不支持 custom grammar/strict/deferred，3 个现有工具 Provider 上限都是 128。
3. WebSocket、background、Conversations、item_reference、流式续传与真正 compaction 不在本轮通用实现范围；需要这些能力的客户端必须按已发布能力选择路径。
4. PostgreSQL 不可用时，取消/异常收尾可能保存失败并记录 request_id 日志；持续写故障时已有进行中记录由 TTL 清理。状态内容包含用户 input/output，配额和 retention 可按运营范围降低。
5. 本地 JAR 有校验快照，源码工作树尚未提交。部署前应固化源码提交和不可变镜像，按本仓库版本规则提升同一候选制品。

## 回滚点

- 部署回滚优先使用上一不可变 Backend/Web/Automation 制品。尚未部署时，工作分支中的变更可整体审查后处理。
- additive schema 可先保留；完全撤销前停止新版本写入、备份网关响应，再回滚 `032-002-tag` 与 `032-001-owned-responses`，恢复上一版本。撤销会删除公共网关状态和原生 Key 归属。
- 已验证新的 migration rollback 不删除已有 Provider schema；未执行生产迁移/回滚，也未覆盖已发布镜像或版本。

复现步骤见 [客户端接入](../integrations/OPENAI_AGENTS.md)，需求边界见 [升级规格](../requirements/OPENAI_AGENT_COMPATIBILITY.md)。
