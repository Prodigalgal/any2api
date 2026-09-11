# 统一 Action / Runtime / API Channel 任务规格

## 目标

将所有上游厂商请求统一收敛到 Python Automation 的 Action/Channel 边界。Java 核心只负责业务语义、账号租约、凭据版本和规范化结果；Python 由 RuntimeChannel 或 ApiChannel 负责账号隔离、代理亲和、上游物理请求和原始事件转译。Runtime 是默认渠道，API 只能按已验证的 Action binding 逐项启用。

这条统一指的是统一 Action 契约和顶层出站边界，不要求所有厂商共享同一套上游字段、签名或页面函数。这里的“官方前端”仅指厂商 Web 产品或 CLI 工具实际使用的前端 bundle、Web API 和 WebSocket，不是厂商公开的渠道 API。前端函数、页面 `fetch`、页面 WebSocket、受控 API HTTP 和必要时的 UI 操作，分别属于对应的 Runtime/API Channel 内部厂商适配策略。

## 范围

- 统一 9 个 provider 的 Action 名称、请求契约、Channel 调度、文本推理、模型发现和账号保活上游访问边界。
- 注册、密码登录、OAuth/token 交换等身份恢复步骤仍由各 provider 的生命周期 adapter
  负责；它们可以使用页面流程或身份协议，但不得成为 Java 推理传输的旁路。
- 明确 Java、Python Runtime、厂商适配器、账号/凭据和观测模块的职责。
- 保留现有 OpenAI 兼容 API、账号选择、租约、凭据版本和 SSE/事件契约。
- 分阶段迁移现有 direct WebClient、curl-cffi browser-shaped HTTP 和独立 WebSocket 路径；每一种迁移都注册为独立 Runtime/API binding。

## 非目标

- 不把所有厂商的请求体、密钥、签名算法或页面函数强行抽成一套字段。
- 不用模拟点击替代可以直接调用的官方前端函数或页面请求。
- 不在本阶段修改数据库核心模型、公开 API 契约或生产环境。
- 不把动态 JavaScript、凭据和任意 header 下发成可执行 Runtime 规则。

## 目标边界

```text
OpenAI-compatible API
        |
        v
Java Core
  语义规范化 / 模型路由 / 账号租约 / 凭据版本 / 结果契约
        |
        | Action + account execution context
        v
 Action Dispatcher
        |                         |
        v                         v
 RuntimeChannel              ApiChannel
 浏览器/context/页面链路       Web API/CLI API 链路
        |                         |
        +------------+------------+
                     v
        上游厂商 Web/CLI 请求链路
```

## 职责边界

### Java Core

- 接收并校验 CanonicalRequest。
- 选择 provider/model/account，获取和续租账号 lease。
- 解密并按版本读取凭据；接收 credential patch 并做版本保护合并。
- 向 Python 发送稳定的 semantic command 和受控 execution context。
- 将 Runtime 事件转成统一 completion/event contract。
- 维护账号状态、冷却、失败分类、用量和审计。

Java 不再直接访问厂商上游域名，不再维护厂商的物理 URL、签名 header、页面 token 拼装或上游流式协议解析。

### Python RuntimeChannel

- 为账号维护独立浏览器 context、storage state、fingerprint、代理亲和和生命周期。
- 使用 Camoufox 作为默认浏览器后端；Patchright 只作为明确记录的兼容兜底。
- 执行厂商官方前端模块、页面 `fetch`、页面 WebSocket 或必要的 UI 操作。
- 将原始响应、SSE、WebSocket 帧和浏览器状态变化转成内部 Runtime 事件。
- 返回 credential patch、runtime diagnostics 和可分类的失败信息。

Python 不决定业务账号是否可用、模型路由、额度、租约和最终账号状态。

### Python ApiChannel

- 执行已审核、已验证的 Web API/CLI API Action binding；不创建浏览器进程。
- 负责对应 API 的请求体、签名、密钥/令牌使用、流式帧和脱敏错误转换。
- 不复用 Runtime 的 cookie、storage state、fingerprint 或页面会话；只接收同一账号的最小凭据快照和代理上下文。
- 没有 API binding 的 Action 必须返回能力缺失，由 Dispatcher 选择 Runtime 或拒绝，不能静默回退成另一种业务语义。

### Action Dispatcher 与 Provider Adapter

- Dispatcher 只负责把统一 Action 分派到 RuntimeChannel 或 ApiChannel，并处理能力缺失和 AUTO 的首个输出前 fallback。
- Java 侧只定义 semantic command 的 provider-specific 字段映射、能力声明和结果解码规则。
- Python 侧 Provider 只注册 `(channel, action)` binding，封装该厂商的 selector/bridge/function/path、签名、上传和事件细节。
- 不把厂商细节泄漏到通用 Runtime，也不让通用 Runtime 反向承载业务规则。

### Account、Credential、Observability

- Account/lease：Java 是状态真相，Python 只执行账号级互斥和 context 复用。
- Credential：Java 保存加密凭据和版本；Python 只接收执行所需快照并返回增量 patch。
- Observability：两端都记录 request id、provider、account、runtime revision、耗时和错误分类；日志禁止输出 token、cookie、完整请求体和密钥。

## 迁移顺序

1. 先固定统一 Action contract、Runtime/API Channel 和旧 transport 兼容转换。
2. 将现有生命周期与 10 个 provider 的 Runtime 推理注册为 Runtime binding。
3. 以 Provider + Action 为单位新增 API binding；MinMax 作为首个 API 样板，API 只覆盖已验证动作。
4. 删除或封存已被 binding 替代的 Java 上游 WebClient、curl-cffi inference 和独立物理 WebSocket 入口。
5. 用 provider/action、账号隔离、保活、切换、credential patch、媒体和测试环境 completion smoke 逐个验收。

## 验收标准

- 生产 provider 的文本推理、模型发现和保活上游域名只出现在 Python Channel/provider adapter；Java 只访问内部 Automation endpoint。
- 同一账号的 inference、keepalive、reauthenticate 按账号串行；不同账号可以并行。
- 账号切换不会复用上一个账号的 context、cookie、localStorage、IndexedDB、fingerprint 或 proxy affinity。
- 前端密钥、接口路径和参数变化只需要修改对应 Python provider/channel binding，不改变 Java 业务编排；身份恢复协议变化仍只影响对应生命周期 binding。
- Runtime 失败可以区分认证失效、风控挑战、上游协议变化、网络/代理失败和模型不可用。
- 至少完成构建、单元测试、隔离测试和一个真实 provider completion 验收后，才允许删除旧路径。

## 当前阶段交付边界

本阶段已完成统一 Action contract、Runtime/API Channel Dispatcher、旧 transport 兼容转换和 Java 的 API/AUTO 选择策略；现有 provider 默认行为仍为 Runtime，MinMax 已拆出首个 API binding（文本/模型/查询动作，媒体上传仍 Runtime-only）。本地 Python 与 Java 全量测试用于证明契约和边界；它们不能替代真实账号的全量 completion、保活、代理切换、账号串行/并行和多媒体验收。生产启用 API/AUTO 前必须按 `provider + action + model + channel + media kind + account` 在 K8S 完成真实验证。

## 多模态验收边界

厂商产品支持图片、文件、视频或音频，不等于当前网页 channel、当前模型和当前账号都支持同一种输入。多模态能力必须按 `provider + model + channel + media kind` 验收，不能从厂商名称推导。当前文本入口的能力声明如下：

| Provider | 图片 | 文件 | 音频 | 视频 | 当前实现 |
|---|---|---|---|---|---|
| Arena | 支持（内联 base64，PNG/JPEG/WebP） | 支持（内联 base64，PDF） | 不支持 | 不支持 | Arena 页面 `uploadFile` + signed upload + `experimental_attachments` |
| Qwen | 支持（内联 base64） | 不支持 | 不支持 | 不支持 | Camoufox 页面内 STS + OSS 上传 |
| MiMo | 支持（内联 base64） | 不支持 | 不支持 | 不支持 | Camoufox 页面内签名上传 + 解析 |
| MinMax | 支持（内联 base64） | 不支持 | 不支持 | 不支持 | Camoufox 页面内临时策略 + OSS 上传 |
| Grok Build | 支持（URL/base64/file ID 透传） | 支持（URL/base64/file ID 透传） | 不支持 | 不支持 | Camoufox 页面 `fetch` 保留 xAI `input_*` block |
| Grok Console | 支持（URL/base64/file ID 透传） | 支持（URL/base64/file ID 透传） | 不支持 | 不支持 | Camoufox 页面 `fetch` 保留 xAI `input_*` block |
| Grok Web | 聊天输入待验收 | 聊天输入待验收 | 聊天输入待验收 | 独立媒体操作 | Gateway 文本与媒体操作分离 |
| DeepSeek | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 等待官方网页上传链路 fixture/live evidence |
| GLM | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 等待官方网页上传链路 fixture/live evidence |
| LongCat | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 当前文本入口不声明 | 等待官方网页上传链路 fixture/live evidence |

Arena 的 Web Search 不是通用工具透传：`provider_options.arena.web_search=true` 经 canonical
command 进入 Arena mapper，并转换为页面协议的 `modality="search"`；模型目录未声明 Search
能力时请求必须失败闭环。Arena 当前只接入 Runtime，API Channel 后置。

每一种已声明输入都必须覆盖：canonical block 形状、该 provider 声明的来源类型（URL/base64/file ID）、
空值和错误 base64、MIME/大小边界、多文件顺序、同账号同代理上传、上传结果注入上游请求、流式与
非流式事件、上游拒绝、超时、取消、账号切换和 credential patch。当前 Qwen/MiMo/MinMax 的页面上传
协议需要浏览器先取得二进制内容，因此只声明内联 base64；URL/file ID 会在 Java 账号租约前拒绝。
未声明类型必须在 Java capability guard 或 Python semantic builder 失败，禁止先上传、转成提示词或
静默丢弃。

本地测试使用无敏感信息的 provider fixture，验证 payload 和 Runtime 调用链；它不能替代
真实账号的四类媒体 completion。发布前需要为每个 `NATIVE` 组合补充有效账号的页面请求、
上传 host、状态码、首个事件、终止事件和账号/代理绑定证据，才可将状态从 `LOCAL_CONTRACT`
提升为 `LIVE_ACCEPTED`。

既有本地矩阵覆盖 9 个 provider 与图片、音频、视频、文件四类输入的 36 个组合；本轮为
Arena 增加了独立的图片/PDF、Search、页面上传导出定位和注册 fixture，并覆盖 Java 入口别名、
嵌套 `source`、内联 MIME/大小边界、上传结果完整性和账号页面上下文绑定；这证明的是契约
和边界行为，不是各厂商真实账号的多模态 completion。
