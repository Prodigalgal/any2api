# 常用 OpenAI API 到厂商 WEB 的桥接补齐

## 目标与授权

用户要求继续处理 0.24.4 的余下问题，并桥接其他厂商。沿用已授权的提交、现有生产 GitOps 部署与现有凭据测试；不创建外部账号或输出凭据。新增厂商 function 桥接属于向后兼容能力扩展，候选使用 0.25.0；已验收候选后修改源码另占新版本。

## 范围

- 八家现有 Provider 的 Chat/Responses 文本、多轮、JSON/SSE；补齐 Arena、DeepSeek、GLM、MinMax、Qwen 的普通 function 定义、tool_choice、call_id、参数、结果回放。
- 复用 ToolEmulationEngine 和现有 Provider/Action/Channel；厂商 WEB 没有原生工具时明确标记 EMULATED，不承诺 strict/schema 原生保证。保留已有搜索、推理、媒体、通道与状态能力。
- 定位 Grok 常用 Chat 无首帧；修复 LongCat 工具图片结果翻译或执行前明确拒绝，输入/适配校验错误不得熔断整个模型。
- 核验取消/超时与 OpenAI 错误透传；继续处理已证实的 Read 往返与目录 SQL 成本，网络/边缘配置只在现有可管理边界内调整。

## 非目标

全量 OpenAI/厂商官方 API、客户端执行策略、增加 namespace/custom 等扩展、音视频/Files 管理等新协议簇、数据库/PV 迁移。无可用账号的厂商完成代码与契约验证，真实 E2E 如实标为未验证，不创建新账号补证据。

## 影响模块

ToolEmulationEngine、Provider manifests/validation/generate、既有 WEB semantic commands、Grok/LongCat Runtime、失败分类与模型读取、SDK smoke、版本配置、测试和本轮验收报告。

## 验收标准

- 已有文本/搜索/推理/媒体和已接入的 MiMo/LongCat/Grok 不退化；调用方工具结果身份与内容不丢失。
- 新增 function 请求可返回标准 Chat tool_calls / Responses function_call，required 未生成有效调用时明确失败；none 不误转换普通文本。非法参数/不支持媒体在租用账号和上游执行前失败。
- SDK 核心模式独立验证普通协议，不被 namespace/custom 扩展失败中断；真实可用厂商记录普通对话/SSE/function 循环、错误及权限，未验组合单列。
- Backend/Automation/版本门禁通过，统一不可变候选；部署后核对 CI/GitOps/Pod/版本、真实 API 和 Read 对比。

## 测试与回滚

相关协议/Provider/失败分类单元及集成测试；官方 SDK 核心 HTTP/SSE、工具回传、权限/无账号/超时/图片；Read 低并发基线与 EXPLAIN。回滚到已验收 0.24.4 四应用不可变镜像；优先避免 schema 变更，保留 032 状态结构。破坏性或新增外部写入需按 AGENTS.md 停止条件另行处理。
