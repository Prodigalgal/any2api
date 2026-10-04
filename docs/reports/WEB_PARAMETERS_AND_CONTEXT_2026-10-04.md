# WEB 参数映射、输入边界与 Xiaomi 桌面端排查（2026-10-04）

## 范围与当前状态

基线为生产 0.26.3 / `2388687`；兼容能力扩展 0.27.0 与可空字段修复 0.27.1 已部署，0.27.1 七家差量及 MiMo 普通工具结果回放已完成。收尾发现 LongCat 目标字段说明与 WEB 控制缓存隔离问题，另占 0.27.2；用户追加七家全部桥接后发布门禁，该候选 CI 已取消、GitOps 未更新，生产保持 0.27.1。后续真实工具组合暴露 MiMo 参数类型及 Responses 资源格式缺陷，修复候选占用 0.27.3。七家使用既有分发 Key、官方 OpenAI SDK 2.54.0、最多 2 个并行探测、客户端 `max_retries=0`，仅发送合成内容。Qwen 已再次确认排除，只有代码证据。以下分别保留本地门禁、生产发布和真实调用结果，不将最终 SDK 成功等同于后台每次尝试成功。

## 参数含义与实际 WEB 目标

| 厂商 | temperature / top_p | 三个 max_* 输出上限别名 | reasoning | search |
| --- | --- | --- | --- | --- |
| MiMo | `modelConfig.temperature` / `modelConfig.topP` | WEB 请求没有对应字段；仅保留既有非约束值策略，低于部署配置 ceiling 明确拒绝 | `modelConfig.enableThinking`，开关映射，不保证 low/medium/high 精确档位 | `modelConfig.webSearchStatus` |
| GLM | `params.temperature` / `params.top_p` | 均映射 `params.max_tokens` | `features.enable_thinking` / `features.reasoning_effort` | `features.auto_web_search` |
| DeepSeek | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `thinking_enabled`，开关映射 | `search_enabled` |
| LongCat | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `reasonEnabled`（WEB 0/1），开关映射 | `searchEnabled`（WEB 0/1） |
| Arena | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | 通用 effort 没有对应字段；由选定模型变体决定 | `modality=search` |
| Grok Web | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | 通用 effort 没有对应字段；由选定模型 mode 决定 | 当前桥接不提供通用 search 开关 |
| MiniMax | 无对应字段，显式值拒绝 | 无对应字段，拒绝 | `model.variant` 的 thinking 开关映射 | 当前桥接不提供通用 search 开关 |
| Qwen（未现场验证） | 根级 `temperature` / `top_p` | 根级 `max_tokens` | `messages[].feature_config.thinking_enabled/thinking_mode/thinking_budget` | `messages[].feature_config.auto_search` |

函数工具由网关生成完整调用约定并解码，调用方执行工具；这属于 emulated function bridge。`store` / `previous_response_id` 属于网关状态能力，不要求 WEB 存在同名字段。参数声明归各 Provider 的 `ProviderProtocolContract.parameterMappings` 所有，公共层不写入厂商 ID 或字段规则。

部署后可以用 `models.retrieve("provider/model")` 查看 `parameter_adaptation`：区分 `mapped`、`toggle_mapping`、`non_binding_only`、`unsupported` 与 `unknown`。详细表只在模型详情返回，目录保留原有字段，避免数百个模型重复携带两份映射表。0.27.0 catalog namespace 为 v6，0.27.2 修正 LongCat 说明另占 v7，避免共享 L2 返回旧声明；能力由当前适配器和模型 metadata 重建，保留历史发现证据及管理员 token overrides。

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
- 0.27.0 发布：Source `cb9b6582c06de2c29ba0a03985002b842dfd4954`、[CI 37202879629](https://github.com/Prodigalgal/any2api/actions/runs/37202879629) success；GitOps `31af2f5d37e1afaa9107d1e9280794e13c13c83a` Synced/Healthy，四组件 Ready/restart=0，两个 Automation project/installed/API 0.27.0 PASS，Web package 与 Server 启动日志均 0.27.0。一次 kubectl 读取 Arena 版本瞬时失败，独立重读通过，保留初次退出状态。

### 0.27.0 上线差量与 0.27.1 修复

- 七家 41 项检查首轮 31 passed / 10 failed。8 项为真实缺陷：七家 Responses 的 `reasoning:null` 被 Parser 冗余 shape 校验拒绝，Arena Chat 的 `reasoning_effort:null` 又穿过 semantic controls allowlist，触发 Automation 422。0.27.1 去除重复检查，保留非 null 类型校验，并让语义控制边界只复制非 null 值、保留显式 false。
- 另外 2 项是探测器误判：MiMo 的流式长输入在首事件/提交 SSE 前已经被拒绝，正确返回 HTTP 400 JSON；原脚本强制 HTTP 200。修正脚本同时接受首帧前 JSON 400 与已提交 SSE 的单一失败终态，补充两协议首事件失败回归。不为通过脚本改变既有错误交付契约。
- 初次窗口（DB time `2026-10-04 12:50:12.933619+00`）含 24 个逻辑推理、26 次后台尝试；DeepSeek 一次 provider_upstream_error、LongCat 一次 empty_model_response 后换号成功，Arena 一次 credential_rejected 后成功。所有 MiMo 长输入拒绝均是 attempt=1 / success=false / output_tokens=0 / context_length_exceeded；网关预校验拒绝没有租用账号，不伪造 usage。窗口包含额外合成诊断请求，不能与 SDK 41 项简单相减。
- 0.27.1 本地 Backend 497 passed / 5 条件 skipped、bootJar、统一版本/JAR 契约和探测脚本 ruff lint 通过；Automation/Web 无业务源码变化，发布 CI 完整执行各门禁，Automation 509 passed（1 条已有 httpx deprecation warning）、Web lint/build 通过。`tools/` 的 3 处新增分支换行在未发布的 0.27.2 候选一并修正，按 Automation 100-column 配置 format/lint 通过，未覆盖旧制品。

### 0.27.1 生产发布

Source `cdf32963909d73b426aa337beed3fb6dc1fe29b8`；[CI 37204392918](https://github.com/Prodigalgal/any2api/actions/runs/37204392918) 全部门禁、四镜像构建及 update-gitops success。GitOps `c59c7eb87a778d101dd1e98254e1397af8e074f0`，Argo Synced/Healthy，四个 Deployment rollout success，四个新 Pod Ready/restarts=0；两个 Automation project/installed/API 0.27.1 PASS，Web package 0.27.1，Server 启动日志 2026-10-04 21:12:17 UTC+8 为 v0.27.1。

四镜像 suffix 为 `20261004-v0.27.1-release-cdf32963909d73b426aa337beed3fb6dc1fe29b8`：

| 组件 | Pod | 镜像 digest（sha256） |
| --- | --- | --- |
| Server | any2api-server-87db7649b-nn4f4 | 48403ac8167d044a075e626f19c3974e5a685c325b94b075c76c6afa5fde724a |
| Automation | any2api-automation-8984fccd4-tkd99 | 1bb7f5b8eb903d6d9dff026dd6d763bb38a3309c01977ac6f7dedae47983e67f |
| Arena Automation | any2api-automation-arena-5d7cbc6797-scxcb | 3aa72c6e316ead1d743bf9d130ec9e5202dc36e1c020ec36747ce4f04ad8009c |
| Web | any2api-web-5877f95558-wqf6g | 909fcf6e782e65cf56c0bf90f74fa9a57060a79c5e3ccf5a969a272b5c7b4dd1 |

0.27.1 差量验收窗口从 DB time `2026-10-04 13:14:06.302359+00` 开始；0.27.0 的失败报告不覆盖。

| Provider / 选定模型 | 参数契约检查 |
| --- | ---: |
| Arena / claude-sonnet-5 | 5/5 |
| DeepSeek / default | 5/5 |
| GLM / glm-5.2 | 8/8 |
| Grok Web / grok-3 | 4/4 |
| LongCat / longcat-flash | 5/5 |
| MiMo / mimo-v2.6-pro | 9/9 |
| MiniMax / MiniMax-M3.1-Flash-Preview | 5/5 |

- 合计 **41/41 PASS**，涵盖模型说明、Chat/Responses 可空参数、支持/拒绝/非法值、GLM 三别名原生输出上限以及 MiMo 长输入错误。GLM 三别名各 UPSTREAM output_tokens=16，对应请求 `94d2ac15-520e-4eba-951b-f789c285f542`、`5795d39e-213d-4c79-b929-248894921c81`、`1b2b581e-a4ad-482c-9e6e-ecd8a1bcbeda`。
- MiMo 无参数 strict 默认值 4/4 PASS；中文 schema/enum 的三个生成请求均完成单一结构化 function call。首次把回显指令放进工具结果，模型拒绝；将指令移回 user 后，`REPLAY_…` 随机标记仍被模型识别为可疑重放。两次 nonce 回显不通过的记录保留，不以 HTTP completed 当作语义验收成功，也不改写模型的拒绝来通过测试。
- 使用普通业务数据的最后一个完整闭环 **2/2 PASS**：`6e24f25b-f412-4b26-a4a8-0cbca7f6c5d4`（中文 enum / SSE function call），`cbfbe663-a394-45ca-8e17-8b0e56eae653`（function_call_output 返回 `status=已核验, processed_pages=37`，模型正确汇报）。这验证当前所选 MiMo 模型的工具输入与结果回放，不能推广到所有工具内容或所有模型。
- SDK 和额外合成工具诊断窗口共 **34 个实际推理 / 35 次后台尝试**；Grok Responses `7720f390-4660-4fe3-933a-8a6388c74103` 首次 empty_model_response，第二次成功。Arena mapped-controls `057a6cdf-dc21-4e8b-bee1-830494da8668` 在 Server 日志明确 `prompt_cache_hit`，没有租用账号/usage 记录，不能算作该次原生参数再次生效的证据。
- MiMo 四个长度错误均 HTTP 400（包括请求 stream=true 但首事件前拒绝）、attempt=1 / success=false / output_tokens=0。关联四账号均 ACTIVE/enabled、cooldown_until=null、active model cooldown=0；七个所选模型的队列/并发均 0、circuit CLOSED。最新滚动目录仍只有 GLM READY，其余 DEGRADED，包含之前真实失败和本轮主动拒绝输入；不能把 Pod Healthy 或 41 项通过描述为七家全部 READY。
- 本轮普通推理 queue_ms 均 0，account_acquire_ms 最大 213ms，DeepSeek 最长 TTFB 63783ms、GLM 42289ms。这些请求的大部分等待在上游首输出阶段；不据此排除其他负载下的网关成本，也不宣称所有 Read/推理性能问题已解决。

### 0.27.2 收尾修复候选

- LongCat 构造实际上由内部 `reason_enabled` / `search_enabled` 映射为 WEB `reasonEnabled` / `searchEnabled` 的 0/1。修正公开参数说明目标，catalog namespace v7 隔离旧 v6 声明；实际推理字段构造没有改变。
- `PromptExactCacheManager` 原 cache key 仅包含 canonical messages/generation，raw WEB controls 未参与。0.27.1 的 Arena mapped-controls 缓存命中直接暴露了这一边界：开关变化也可能命中 plain 结果。现在只允许明确的原始协议字段和已在 generation key 中的字段使用精确缓存，其余非 null 控制绕过缓存读写；null 缺省和普通缓存保留。prompt key v3 隔离旧条目，代价是受控请求减少缓存命中、更多真实上游生成。
- 更新旧文档中 function=Unsupported / state=Unsupported 的过期表述，明确现有 emulated function 与 Gateway state，并区分 skill 说明、调用方执行及 WEB 扁平 prompt 的优先级边界。
- 新增普通缓存不会被 search/thinking 控制读取/污染、显式 false、nullable 和不同 canonical sampling key 的回归。0.27.2 本地 Backend 499 passed / 5 条件 skipped、bootJar、版本/JAR、探测脚本 format/lint 通过。Source `76202aa` 的 [CI 37206740522](https://github.com/Prodigalgal/any2api/actions/runs/37206740522) 在新增发布门禁后取消：三项质量门禁成功、镜像 job cancelled、update-gitops 未执行；不复用可能已经部分构建的候选镜像。

### 0.27.3 七家完整 Agent 桥接门禁

探测使用 `tools/compatibility/openai_web_bridge_smoke.py`。每家七项：模型详情、Chat JSON strict 工具提取、Chat SSE 客户端结果、Responses SSE 指定 strict function、Responses JSON 完整回放、store/previous_response_id/retrieve/input_items、测试资源删除。参数必须来自不同角色的合成信息：system 的文档名、developer 的 SKILL.md 规则、超过 32 条消息历史中最早的批次号；工具结果由客户端提供真实合成业务字段。不能通过将期望答案写入 enum 或最后一条消息来替代历史验证。

| 厂商 / 本轮模型 | 当前已证实结果 | 证据环境 |
| --- | --- | --- |
| Arena / claude-sonnet-5 | 七项全部通过 | 生产 0.27.1 |
| DeepSeek / default | 前五项通过；原执行中断后，保存状态续接和清理两项补验通过 | 生产 0.27.1 |
| GLM / glm-5.2 | 前五项通过；原执行中断后，保存状态续接和清理两项补验通过 | 生产 0.27.1 |
| Grok Web / grok-3 | 七项全部通过 | 生产 0.27.1 |
| LongCat / longcat-flash | 七项全部通过 | 生产 0.27.1 |
| MiniMax / MiniMax-M3.1-Flash-Preview | 七项全部通过 | 生产 0.27.1 |
| MiMo / mimo-v2.6-pro | 候选真实 WEB Chat 往返、Responses 调用/完整回放/状态续接、严格资源 schema 和清理全部通过 | 本地 0.27.3 协议/PG/认证，真实现有 WEB 账号及候选 Python 构造器；不含生产协调器 |

关键请求：Arena `ab7c780d-38d3-4752-bd26-823fd8c79584` / `7246b2ff-88f7-4899-a76e-067493b2af0e` / `a9c58193-1dc9-4a21-b379-4029a6f56aa8`；DeepSeek 补验 `9301bc2d-5675-4014-9dff-46f98f1d8a58`；GLM 补验 `91b62815-6494-4455-807b-8e8df09f40f7`；Grok state `3aac084f-3324-4730-9794-01cf64ec57a8`；LongCat state `9bbbc447-2890-4a81-9d0b-b7f3f80ca2ab`；MiniMax state `6d8637c7-082b-4876-aad7-d832d6c5b1ff`。前六家完成的功能续接不代表其旧 `input_items` 符合 SDK 所有类型：该项格式修复须通过新候选的 schema 验收。

本轮修复与验证：

- **MiMo 无类型参数**：生产 Chat `4de9d4a4-b63d-421b-9eb2-2aa788eab6fc` 三次后台尝试和 Responses `8a91102e-3df3-4b05-ac5b-626463e99764` 均失败于严格类型。独立合成 native canary HTTP 200，原文为 `<|MiMoML|parameter name="batch_number">11733</|MiMoML|parameter>`，其 schema 明确 string。旧解码器 `readTree` 误转数字；按声明类型映射 MiMoML/XML/key=value 文本，处理 nullable、anyOf、局部 ref，限制 32 层/2048 节点；显式 JSON 对象保持原类型并继续严格拒绝错误。新增回归修复前 3 failed，修复后 13 项 MiMo 协议测试全部通过。候选真实 Chat `cfa4a321-0a9d-46db-a7d3-edca6b4733bf` / `d3e5a606-773b-448a-9043-0ffe3b7861c7`，Responses `06859ebd-9a0f-4dc0-bc1a-f46c4e427361` / `551787da-45e7-4ba5-9f4b-ac1a1cd978fe` 均通过合成上下文和结果语义检查。
- **MiMo 完整历史**：修正带 `tool_calls` 的 assistant 正文被丢弃以及调用 ID 未进入 WEB 文本；空调用列表同样保留正文。4 个 string/text-block × 有/无调用回归、七家 builder 的完整角色/超过 32 条历史/调用结果保留测试通过。不得变更客户端输入对象。
- **Responses 资源格式**：读取时将存储的 easy message 字符串投影为 SDK 内容块；assistant 使用 output_text/annotations，其他角色使用 input_text，消息与 function/custom 调用及结果缺省状态 completed。实际续接先完成生成后，SDK 曾揭示工具结果缺必填 status；完整工具资源测试复现失败并补齐读取投影。原有媒体、annotations、phase、显式状态、ID/分页及存储内容保留，不做 DB 重写。真实 PG 回归和官方 SDK 2.54.0 的 `TypeAdapter(ResponseItem)`、严格 serialization、四角色及四种工具资源、asc/desc/cursor 与资源重新提交均通过，本地资源两组 2/2；新增 `openai_response_resource_smoke.py` 可复现。
- **MiMo 最终候选**：真实 WEB Responses 调用 `356ab22c-09d0-49a8-9eac-1ce5b8665bba`、完整回放 `5d027a95-cff5-4a43-b451-fdb8b5cb2f52`、state `c5a299cb-f327-45dd-b86a-e01b7731e0c4` 全部通过；state 的官方 SDK resource schema 校验通过，两个测试资源均删除后 404。与此前已通过的 Chat 两项合并，七项功能覆盖齐全。共享协议及状态候选门禁已完成，进入发布后七家同版本复测。
- **质量门禁**：Backend 全量 511 tests：506 passed、5 条件 skipped，bootJar 与八处源码/JAR 版本 0.27.3 PASS；Automation 全量 520 passed，Web lint/build 与 Python ruff 检查通过。无 DB、凭据、账号或 Key 变更。
- **保留失败**：首轮 Arena/GLM/部分 DeepSeek 在租约前返回 `coordination_unavailable`，不是 WEB 拒绝；恢复后重测。DeepSeek/GLM 第一次执行被用户消息中断，未执行项经原保存状态补验并清理。MiMo 候选 isolated native 测试曾出现探测辅助进程失败，Responses 后续项未执行/未通过，原报告保留，不能用 Chat 成功替代 state 验收。

故障窗口内 Server 到跨节点 Redis 的只读检查：PING 76.866–77.186ms，159,576-byte catalog GET 387.905/173.909ms，Server 本地 health 25.654ms、ready 88.556ms。Redis 无 blocked client/eviction，Pod 无重启；缓存 250ms 预算确实可能被大 value 的一次 GET 超过，但这不证明租约 3s 超时根因。没有修改 Redis timeout、连接、部署位置或 PV，可靠性和全目录性能根因仍未关闭。

### 目录 Read 的有界检查

同一 direct 入口、现有 Arena Key，每个端点每窗口 3 个样本，均 HTTP 200。全目录 259 个模型，解压 JSON 约 991KB，gzip 约 25.6KB；新参数表仅详情提供，目录没有重复增加。

| 窗口 | `/v1/models` median ms | 单模型详情 median ms |
| --- | ---: | ---: |
| 0.26.3 发布前 | 5715.71 | 266.50 |
| 0.27.0 发布后 | 2061.55 | 1799.62 |
| 0.27.1 发布后 | 1606.49 | 786.17 |

0.27.1 全目录范围 1101.59–2649.94ms，详情 214.50–1279.94ms。这些不同时间的 n=3 样本受 WAN、重定向与缓存影响，详情中位数也有恶化，不能推断所有 Read 变快或把差异全部归因于本轮代码。全目录体积与跨节点/公网延迟仍是后续优化项；本轮没有重构目录查询、迁移 Redis 或改动生产 timeout。

## 遗留与回滚

未知精确 token 上下文、全部模型/账号/mode、采样统计、多模态 token 成本、GLM 截断终帧，以及 Xiaomi 默认 Agent 引导的长期控制仍需独立证据。当前不将完整 Agent 输入拆成 WEB 上传文件，不自动压缩/截断历史来掩盖边界；上传/RAG 是否保留 system 与工具语义需要另做实现和验收。

没有数据库/凭据迁移。0.27.3 后续发布的直接回滚点为 0.27.1 四组件不可变镜像 suffix `20261004-v0.27.1-release-cdf32963909d73b426aa337beed3fb6dc1fe29b8`；会恢复已记录的缓存、MiMo 参数/历史、资源格式缺陷，历史 usage 不原地改写。整轮之前的 0.26.3 基线 suffix `20261004-v0.26.3-release-2388687ea3675e14a24dead88bb91f37b4663ceb` 为历史回滚点，其旧 catalog v5 与本轮 v6/v7 隔离。
