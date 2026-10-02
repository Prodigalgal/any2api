# OpenAI agent 协议升级（0.24.0）

## 目标与范围

沿用 CanonicalRequest / CanonicalEvent、InferenceCoordinator 和现有 Provider，完善 Chat Completions 与 Responses，使 Codex HTTP/SSE 和官方 SDK 能完成工具调用、结果回传、最终回答和后续对话。

范围：历史回放、function/custom/namespace 工具桥接、参数校验、流式生命周期、长上下文保护、缓存隔离、Responses 状态资源、API Key 权限、模型能力和客户端验收。

非目标：本轮不实现 WebSocket、background、Conversations、原生 OpenAI hosted tools 或真正的语义 compaction；不把模拟工具声明为原生 strict/grammar 能力。

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
4. 验收：后端/Automation/版本校验、官方 SDK、真实 Codex 只读工具循环、发布与回滚记录。

## 验收标准与验证方式

- 官方 SDK 能消费 Chat/Responses 流式、非流式，并正确重建工具参数和最终输出。
- Codex 指定版本能够调用本地只读工具，回传结果，得到最终回答；区分可控上游 fixture 与真实厂商认证 E2E 的证据。
- 覆盖并行工具、custom/namespace、reasoning 回放、超过 32 条消息、取消、超限、非法字段、无账号、限流和上游故障。
- 状态支持续接、查询、删除、分页、过期；跨 API Key 不可访问，重启可读取。
- Backend tests 全量执行，Automation 相关测试通过，版本统一为 0.24.0；新迁移可回滚。
- 发布候选必须是不可变制品。生产部署及真实厂商凭据使用遵循 AGENTS.md Stop Conditions，在准备可审查候选后执行授权边界。

## 参考

- https://learn.chatgpt.com/docs/enterprise/gateway-compatibility
- https://developers.openai.com/api/docs/guides/function-calling

## 进度

- [x] 阶段 1：现状审查、兼容契约与基线测试（349 项，5 项 live 条件跳过）。
- [x] 阶段 2：核心协议与工具循环。
- [x] 阶段 3：状态资源、隔离与能力声明。
- [x] 阶段 4 本地验收：官方 SDK、真实 Codex CLI 的只读图片工具闭环、真实 PostgreSQL 迁移/回滚、候选构建。
- [ ] 阶段 4 环境验收：授权的真实厂商推理、测试环境部署和不可变镜像验收；需按 AGENTS.md Stop Conditions 确认环境/凭据使用与外部写入。

验收配置、证据和限制见 [客户端接入](../integrations/OPENAI_AGENTS.md) 与 [验收记录](../reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)。
