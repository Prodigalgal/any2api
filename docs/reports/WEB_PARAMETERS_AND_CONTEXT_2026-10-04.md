# WEB 参数映射、输入边界与 Xiaomi 桌面端排查（2026-10-04）

## 范围与当前状态

基线为生产 0.26.3 / `2388687`；本轮兼容能力扩展候选为 0.27.0。七家使用既有分发 Key、官方 OpenAI SDK 2.54.0、最多 2 个并行探测、客户端 `max_retries=0`，仅发送合成内容。Qwen 沿用用户此前排除范围，只有代码证据。本报告的上线状态与最终差量结果将在候选发布后补充，不把本地测试当作已部署。

## 参数含义与实际 WEB 目标

| 厂商 | temperature / top_p | 三个 max_* 输出上限别名 | reasoning | search |
| --- | --- | --- | --- | --- |
| MiMo | `modelConfig.temperature` / `modelConfig.topP` | WEB 请求没有对应字段；仅保留既有非约束值策略，低于部署配置 ceiling 明确拒绝 | `modelConfig.enableThinking`，开关映射，不保证 low/medium/high 精确档位 | `modelConfig.webSearchStatus` |
| GLM | `params.temperature` / `params.top_p` | 均映射 `params.max_tokens` | `features.enable_thinking` / `features.reasoning_effort` | `features.auto_web_search` |
| DeepSeek | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `thinking_enabled`，开关映射 | `search_enabled` |
| LongCat | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `reason_enabled`，开关映射 | `search_enabled` |
| Arena | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | 通用 effort 没有对应字段；由选定模型变体决定 | `modality=search` |
| Grok Web | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | 通用 effort 没有对应字段；由选定模型 mode 决定 | 当前桥接不提供通用 search 开关 |
| MiniMax | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `model.variant` 的 thinking 开关映射 | 当前桥接不提供通用 search 开关 |
| Qwen（未现场验证） | 根级 `temperature` / `top_p` | 根级 `max_tokens` | `messages[].feature_config.thinking_enabled/thinking_mode/thinking_budget` | `messages[].feature_config.auto_search` |

函数工具由网关生成完整调用约定并解码，调用方执行工具；这属于 emulated function bridge。`store` / `previous_response_id` 属于网关状态能力，不要求 WEB 存在同名字段。参数声明归各 Provider 的 `ProviderProtocolContract.parameterMappings` 所有，公共层不写入厂商 ID 或字段规则。

部署后可以用 `models.retrieve("provider/model")` 查看 `parameter_adaptation`：区分 `mapped`、`toggle_mapping`、`non_binding_only`、`unsupported` 与 `unknown`。详细表只在模型详情返回，目录保留原有字段，避免数百个模型重复携带两份映射表。缓存 namespace 升为 v6，能力由当前适配器和模型 metadata 重建，保留历史发现证据及管理员 token overrides。

### 探测证据与含义

- 0.26.3 首轮 38 个参数/输入案例：GLM 的 temperature、top_p、128/128000 输出上限、reasoning:none 被接受；MiMo 的 sampling、128000 ceiling、reasoning:none 被接受。其他厂商的无对应字段在网关预校验拒绝，不能说是直接发送后被厂商拒绝。
- GLM `glm-5.2`：要求生成 100 行，设置 `max_output_tokens=16`，上游 usage 为 16，request `ff57e429-d4aa-4795-a478-3475b88627ec`。这证明该选定组合的上限映射确实限制输出；不证明其他模型、全部采样行为或所有 effort 档位。
- temperature/top_p 字段构造和 HTTP 接受已确认；采样分布变化未做统计验证。开关关闭的接受不等于支持所有强度档位。
- MiMo 128000 并未发送成原生 max 字段。`ANY2API_PROVIDER_MIMO_WEB_OUTPUT_TOKEN_CEILING` 默认 65536 是既有部署策略，详情明确 `enforced:false`；不承诺能生成 128K。当前公开 bundle 的静态 `maxCompletionTokens=65536` 也不能替代 v2.6-pro 的逐模型、最大输出实验。
- GLM 本轮 cap=16 的 Responses 仍显示 completed；没有本轮原始 finish_reason 证据，未按 token 数相等猜测 length/incomplete，后续需要取得原始终帧再判断。

## tools / skills 能否移到原生 WEB 的其他字段

用户追加要求后，使用当前已有账号的原生 WEB 通道进行独立合成探测，绕过网关参数预校验但不增加公共 passthrough。只发送一个无副作用函数（参数 number=7，nonce 为随机 enum），或包含随机回复标记的短技能；不执行工具、不安装技能、不修改 Agent/MCP 配置、不保存凭据补丁。字段名称和 schema 是待验证假设，HTTP 200 不作为功能生效证明。

| 选定通道 | 发送位置 | 当前证据 |
| --- | --- | --- |
| MiMo `/open-apis/bot/chat` | 根级 tools + tool_choice、functions + function_call、skills、systemPrompt；modelConfig.tools、modelConfig.systemPrompt | 6 个负向案例均 HTTP 200，但函数名/nonce/技能标记未出现，无结构化自定义调用。相同 system 和工具说明放入 query 的 2 个正向对照均命中标记，工具对照生成 MiMoML 调用文本 |
| DeepSeek completion | 根级 tools + tool_choice、skills | 两个请求均 HTTP 200，未出现自定义函数或技能标记，未发现结构化自定义调用 |
| LongCat chat-completion | 根级 tools + tool_choice、skills | 两个请求均 HTTP 200，未出现自定义函数或技能标记，未发现结构化自定义调用 |
| Grok Web gateway | `session.create.session.tools/tool_choice`、session.skills | 会话及生成流程完成，未出现自定义函数或技能标记，未发现结构化自定义调用 |
| GLM completion | 根级 tools + tool_choice、skills | 原生 HTTP 200 中 tools 返回 INTERNAL_ERROR，无有效调用；skills 请求生成完成但未使用技能标记。不能把 HTTP 成功包装成 tools 支持 |
| Arena direct-battle | 根级 tools + tool_choice、skills | 首轮 HTTP 200，但临时探测器未正确重建 prefix-coded 流，不用作语义结论。修正后复测遇到 HTTP 429，已停止；这两个字段能否生效仍未验证 |
| MiniMax message | 根级 tools + tool_choice、skills | 修正探测模型 ID 后，Runtime 两个请求均 HTTP 200、生成结束，无自定义函数/技能标记。先前 API/Runtime 在发送前失败记录保留，不作为能力结论 |

公开前端中的 `tool_calls` 可能仅用于渲染厂商自身工具结果。GLM 的 `mcp_servers` 是厂商 MCP 连接入口；MiniMax 的 `AgentConfiguredDefinition.tools/skills` 为列表标量，另有 skill 安装/启用和 runtime skills 接口；LongCat bundle 包含 AG-UI RunAgentInput schema。这些只证明存在另一套结构或配置，尚未证明当前聊天端点能接收任意客户端 function schema、把调用交还客户端，并用客户端结果继续。未把这些入口直接加入映射。

Xiaomi 客户端的 skills 已作为 system 文本发送，网关收到的并非可独立转发的技能资源。本轮只有把说明保留在实际模型输入中的正向对照有效，因此继续保留完整工具模拟契约。即使未来找到有效独立字段，模型仍要读到对应说明；应另行核实平台按哪个字段计算输入限制，不能承诺移字段就能避开上下文窗口。

取证位置（均为本地忽略的合成报告）：`backend/build/mimo-native-fields-20261004.json`，LongCat/DeepSeek/Grok 同目录的 native-fields 文件，GLM 的 decoded 文件，MiniMax 的 corrected 文件，Arena 的 final 文件。MiMo root/tools probe `d8a90564093b48dca633ffa370c94591` 与 query 正向 `bfe9c1ccb2094b73b9467979636bbe66`；GLM error `6557bb00124b41d79119fb454c9503f2`；MiniMax corrected tools `955cfd12168c4d63af1c2ac4ec927e75`。这是原生临时探测 ID，不是网关 request_id；不伪造入库 usage 证据。

## WEB 输入限制与模型上下文分开记录

以下字节/字符指探测构造的 instructions，仍需加上短 user input、角色分隔符及工具约定；不是整个请求的 token 数。没有官方 tokenizer/上游 token 证据时不换算。语义回忆或长上下文质量未做完整评估。

| 厂商/选定模型 | 当前来源 | 本轮现场边界 | 模型 token 上下文结论 |
| --- | --- | --- | --- |
| DeepSeek/default | 认证模型目录 `input_character_limit=2621440`；公开前端读取同字段 | 128K、256K ASCII 字符被接受并返回确认标记；usage 是 ESTIMATED | 2,621,440 是厂商定义的字符上限，不能填进 max_context_tokens；精确 token 窗口未知 |
| GLM/glm-5.2 | 当前前端 `wd=9e5`，对文本及粘贴文件内容按字符累计检查 | 128K ASCII 返回确认；256K 被接受，上游 input_tokens=44542，未返回预期标记 | 前端约 900,000 字符限制不证明模型窗口等于该数；完整长输入语义未验收 |
| LongCat/longcat-flash | 当前目录未返回限额；前端已读取，尚未找到可信对话上限字段 | 128K、256K ASCII 返回确认，上游 input_tokens 分别 22717、44977 | 只说明该合成输入被接受，精确上限未知 |
| Grok Web/grok-3 | 当前目录无上限；匿名前端读取 403 | 128K、256K ASCII 返回确认，usage 是 ESTIMATED | 精确上限未知，不能按 ESTIMATED 宣称 token 窗口 |
| MiniMax/M3.1-Flash-Preview | 当前目录仅 provider_id；前端存在 context_limit 配置入口，但当前桥接没有为它声明原生映射 | 128K、256K ASCII 返回确认，usage 是 ESTIMATED | 需核对认证模型配置与所选 context_limit，不能套用官方付费 API 规格 |
| Arena/claude-sonnet-5 | 当前目录没有上限；历史 FastChat 默认值不代表现站配置 | 4K、16K、64K、96K、112K ASCII 返回确认；128K 被服务端拒绝，HTTP 400 `Invalid user message content` | 当前合成输入的接受/拒绝边界在 112K 与 128K 之间；精确硬限仍未知，不按模型宣传规格设置 1M |
| MiMo/mimo-v2.6-pro | 当前 bot config 没有明确 input/context token 限额 | 约 100K 与 102K ASCII 被接受，120K/128K 被固定长度拒绝；32K 中文通过，64K 中文被接受（上游 input_tokens=44855），但没有确认标记 | 字符、UTF-8 字节、token 的边界不能混用；当前模式边界仅在 102K 与 120K 之间，不能声明统一 1M |
| Qwen | 本轮排除真实调用 | 未验证 | 未知 |

### Arena 历史说明的核对

用户提供的材料中的 12,000 字符、50 轮，能在 [FastChat constants.py](https://github.com/lm-sys/FastChat/blob/main/fastchat/constants.py) 找到默认值；代码也允许环境变量覆盖输入值。本轮当前 `arena.ai` 桥接实际接受 112K 字符，不能把历史默认值作为当前 direct-battle 的硬限。

[Arena 当前官方会话限制说明](https://help.arena.ai/articles/3975292349-arena-troubleshooting-session-token-limits) 确认存在会话 token/context 限制，并描述 Agent Mode 的压缩；没有给出统一 12K/32K/50 轮的现站规格。当前本项目使用 direct-battle，每次把规范化 system、历史和工具约定构造为一次 WEB prompt，网站原会话轮数不能直接套到 API 逻辑会话上。

### 公开来源与可复核位置

- [DeepSeek 当前前端](https://fe-static.deepseek.com/chat/static/main.6fca03582d.js)：`input_character_limit` 使用点；数值取自本轮认证目录，而非匿名源码常量。
- [GLM 当前前端](https://z-cdn.chatglm.cn/z-ai/frontend/prod-fe-1.1.98/assets/index-CW-6rQtP.js)：`wd=9e5`、`char_count`、`Text input is too long` 相邻逻辑。
- [MiMo 当前前端](https://aistudio.xiaomimimo.com/main.911869f9.chunk.js)：modelConfig 构造与静态 `maxCompletionTokens`；current bot config 的 v2.6-pro generation 为空。
- 这些 hash/版本只代表 2026-10-04 抓取。匿名页面限制、目录声明、服务端拒绝和模型完整上下文是不同证据。

## Xiaomi MiMo AI 桌面端为什么“你好”也过长

本机只读核实安装版本 `26.929.292248`。实际匹配请求 `b17504b0-cba6-434a-bcd7-dff38fa56ad0`（2026-10-04 18:43:07 UTC+8）是 **Chat Completions**，并非该次实际发送 Responses：`max_tokens=128000`，messages 为 system 与 user，无图片。

| 组成 | 实际规模 |
| --- | --- |
| system | 64,229 字符 / 70,408 UTF-8 bytes |
| 末尾技能部分 | 约 32,779 字符，属于 system 的一部分 |
| tools | 30 个；description 合计 61,118 字符 |
| user | 4 个 text blocks，1444 + 927 + 346 + 2 字符；最后一块确实是“你好” |
| 入库请求 JSON | `octet_length(input_snapshot::text)=165339` bytes；网关再把工具 schema/说明编入 WEB prompt，这不是未经解析的 HTTP wire 大小 |

发送量来自客户端自动附带的 Agent 引导、技能目录、环境上下文和工具定义。ASAR 中 `out/main/index.mjs` 的 `q_()` 拼接 claude/desktop-base，`FV()` 给 build/plan/compose 等 agent 写入 prompt；`out/main/node.mjs` 的 `LLM.run` 添加 system、插件 transform 和输入 system。自定义 Key 的目标 URL 由 `au()` 构造为 `base_url/chat/completions`。这些与本轮真实请求结构相符；没有修改安装包或客户端配置。

未发现长度≥80字符的完全重复段落；工具 description 也未整段重复出现在 system 中。此结果不排除语义重复，不把正常工具 schema 和必要权限说明任意删除。客户端填的 1M/128K 是它的配置，不会增大厂商 WEB 接受边界；切换空项目、减少自动加载技能/可用工具可能降低输入，需要在客户端明确选择，网关不静默裁剪。

## 0.27.0 changelog 与验证

- 参数契约：各 Provider 声明原生目标；模型详情新增适配方式及输入证据，目录避免重复表；已落库旧能力不覆盖当前 adapter，管理员 overrides 和 discovery 证据保留。
- 请求校验：已知可选 generation 字段的 null 按缺省处理，非空不支持字段与未知 null 字段仍拒绝；GLM 三个 max_* 别名必须为正整数，提前失败。
- MiMo：完整 Unicode 工具 schema 不再转成 `\\uXXXX` 膨胀；保留独立空白流片段；只识别完整、固定的长度拒绝，普通引用或相似文本保持原结果。非流式返回 400 `invalid_request_error` / `context_length_exceeded`；已提交 SSE 使用失败终态，`retryable:false`、不换号、不误判凭据、不打开模型熔断。
- 兼容性：不改变常用 Chat/Responses 工具、历史、媒体、权限与默认存储规则。MiMo 长输入原先是 HTTP 200 completed 的拒绝文本，现改为失败；调用方须处理正确的错误。Arena max_* 仍不支持，不能承诺通过重命名就能实现。
- 本地：Backend 492 passed / 5 条件 skipped，Automation 509 passed，Web lint/build，0.27.0 版本与 JAR 契约通过。保留首轮 whitespace 测试失败、后端 Provider 隔离失败及修正记录，未弱化测试门禁。
- 复现入口：`tools/compatibility/openai_parameter_smoke.py` 与 `web_context_probe.py`。Key 仅从保护文件或既有配置读取，报告保存结果/大小/request_id，不包含 Key 或用户全文。
- 生产发布、GitOps、Pod/API 与逐厂商上线后验收：待补充。

## 遗留与回滚

未知精确 token 上下文、全部模型/账号/mode、采样统计、多模态 token 成本、GLM 截断终帧，以及 Xiaomi 默认 Agent 引导的长期控制仍需独立证据。当前不将完整 Agent 输入拆成 WEB 上传文件，不自动压缩/截断历史来掩盖边界；上传/RAG 是否保留 system 与工具语义需要另做实现和验收。

没有数据库/凭据迁移。回滚四组件至 0.26.3 不可变镜像 suffix `20261004-v0.26.3-release-2388687ea3675e14a24dead88bb91f37b4663ceb`，旧 catalog v5 与新 v6 隔离；回滚会恢复 MiMo 原先把拒绝计成功的缺陷，历史 usage 不原地改写。
