# OpenAI Chat/Responses 到厂商 WEB 的桥接（当前 0.24.4）

## 目标与范围

用户于 2026-10-02 明确：调用方只使用 OpenAI API，各厂商上游均按 WEB 接入；本项目负责后端到厂商 WEB 的桥接，只完善 Chat Completions / Responses 的常用接口与行为。

沿用 CanonicalRequest / CanonicalEvent、InferenceCoordinator、Provider 和现有 WEB 请求 / Browser Runtime 通道。代码中已有的 API / RUNTIME / AUTO 是内部通道选择，保持兼容；不由其名称推导需要对齐厂商官方 API。工具由调用方执行，后端只转译工具定义、调用参数和回传结果。

| 常用入口 | 本轮交付范围 |
|---|---|
| `GET /v1/models` | 可用模型发现，保留现有权限和路由契约 |
| `POST /v1/chat/completions` | 文本/多轮历史、JSON/SSE、function tools、工具结果回传、usage 与 finish_reason |
| `POST /v1/responses` | input/instructions、JSON/SSE、function_call/function_call_output、输出与终态；已实现的 store/previous_response_id 按 Key 隔离续接 |
| 图片输入与工具图片结果 | 按厂商 WEB/模型能力支持；不能支持的形态在上游执行前明确拒绝，不丢媒体或改变 call_id/role 语义 |

常用 generation 参数和 tool_choice（auto/none/required/指定 function）按 Provider 能力校验；不承诺所有厂商支持相同参数、工具或媒体。输入、输出、取消、超时、认证/授权和上游故障必须有一致的 API 行为。

非目标：全量 OpenAI/厂商官方 API 兼容；新增音视频、Files 管理等协议簇；WebSocket、background、Conversations、hosted tools、语义 compaction、strict/grammar/deferred、加密 reasoning。namespace/custom 等已实现扩展保留，不作为各厂商常用链路的强制验收项。本项目不承接 Codex 专属模型目录、客户端配置或本机工具执行策略。

## 契约与关键取舍

- 保留 `store` 缺省 false 的既有行为；`store:true` 使用 PostgreSQL 网关状态，保留 24 小时，可配置。Redis 不承担权威历史。
- `previous_response_id` 优先解析调用方拥有的网关状态；历史 Grok 原生状态维持账号亲和，增加归属校验。
- 状态按 API Key 归属，API Key 失效/权限变化后重新授权；查询、删除、input_items 支持资源所有者访问。配置的全权限系统入口共用 `system` 归属，分发 Key 使用独立 UUID 归属。
- custom/namespace 转为现有 function 协议，输出还原原类型、名称和 namespace。strict=true、grammar 和 deferred tool search 在无实现保证时明确拒绝。
- reasoning summary 来自已有上游 reasoning，历史 reasoning 保留在原始 input；不伪造 OpenAI encrypted_content。仅加密且上游无法回放的输入明确拒绝。
- 上下文裁剪保留 system/developer 和完整工具调用组；`truncation:disabled` 超限明确报错，不声称已做语义 compaction。
- 工具/状态/结构化/推理请求首先绕过不完整的文本缓存；普通文本缓存保留并补全事件和身份。
- 输出项索引、ID、创建时间在流式和最终响应中一致；终态区分 completed / incomplete / failed，开流前错误保留 HTTP 状态。
- 创建与终态更新分开；完成、异常和取消仅更新仍存在的进行中记录，删除或过期后不重新插入。取消收尾等待初始写入结束，避免数据库提交与断连竞态。
- 使用独立 `gateway_responses` 表承载公共资源契约；既有 foundation `responses` 的 tenant/session 结构和 Grok `provider_response_states` 原生账号亲和结构保持各自边界。网关历史续接使用完整 input/output 回放，随机入口固定原 provider/model。

## 阶段与影响模块

1. 契约及样本：本文件、协议测试 fixture、客户端兼容脚本。
2. 核心链路：protocol、cache、auth feature detector、Provider validation/tool bridge、现有工具 Provider。
3. 状态与能力：Responses service/store/controller、Liquibase 032、ModelCapabilityContract、Grok 状态归属。
4. 验收：后端/Automation/版本校验、标准 OpenAI API 与官方 SDK、真实厂商 WEB、发布与回滚记录。Codex smoke 保留为可选补充证据。

## 验收标准与验证方式

- 官方 SDK 能消费 Chat/Responses 流式、非流式，并正确重建工具参数和最终输出。
- 调用方可执行 function 工具并按 call_id 回传结果，后端桥接厂商 WEB 取得最终回答；使用标准 API 验收，不要求后端执行 shell/patch 等本地工具。区分可控上游 fixture 与真实厂商认证 E2E。
- 常用路径覆盖多轮历史、工具结果、图片能力边界、取消、超限、非法字段、无账号、限流和上游故障；已有并行/custom/namespace/reasoning 行为保留回归，不为完成常用接口继续扩展协议。
- 状态支持续接、查询、删除、分页、过期；跨 API Key 不可访问，重启可读取。
- Backend tests 全量执行，Automation 相关测试通过，当前发布版本统一为 0.24.4；新迁移可回滚。0.24.0 保留为新增协议/迁移的历史引入版本。
- 发布候选必须是不可变制品。用户已授权生产部署和现有厂商凭据测试；新增外部账号、凭据或破坏性操作仍遵守 AGENTS.md Stop Conditions。

## 参考

- https://learn.chatgpt.com/docs/enterprise/gateway-compatibility
- https://developers.openai.com/api/docs/guides/function-calling

## 进度

- [x] 阶段 1：现状审查、兼容契约与基线测试（349 项，5 项 live 条件跳过）。
- [x] 阶段 2：核心协议与工具循环。
- [x] 阶段 3：状态资源、隔离与能力声明。
- [x] 阶段 4 本地验收：官方 SDK、真实 PostgreSQL 迁移/回滚、候选构建；历史 Codex 图片实验单独保留。
- [x] 阶段 4 发布：0.24.4 四应用不可变镜像、CI、GitOps Synced/Healthy、Pod/版本核验。
- [x] 阶段 4 核心 WEB 桥接：最终 MiMo/LongCat 的 Chat/Responses 官方 SDK 7 组均通过；Grok 的普通 Responses/SSE/function 续接前三组通过。
- [ ] 后续常用能力：Grok Chat required function 120s 客户端超时；LongCat 工具图片适配与输入错误熔断隔离；逐厂商补齐实际需要的工具/图片成功与拒绝验证。Grok namespace 失败保留为扩展限制，不否定已验证的 Responses 普通 function 路径。

接入与能力边界见 [OpenAI API 桥接](../integrations/OPENAI_AGENTS.md)。当前部署、真实厂商和性能证据见 [发布验收](../reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)；[0.24.0 本地验收](../reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)为历史记录。
