# 厂商 WEB 参数取证与适配

## 目标与授权

以 0.26.3 为基线，按用户批准逐厂商探测并修正 Chat/Responses 到 WEB 的参数、输入边界和错误桥接。新增可查询的参数适配说明属于兼容能力扩展，首个部署候选占用 0.27.0；候选部署后再修改源码必须另占版本。沿用已有提交、GitOps 部署及现有分发 Key 测试授权。

用户追加发布门禁：七家厂商完成完整 Agent 桥接验收后再统一发布；Qwen 已再次确认排除。0.27.2 自动发布流水线 `37206740522` 已取消，`update-gitops` 未执行，生产继续保持 0.27.1。后续代码候选不得在验收完成前推送 main 或触发生产部署。

本轮修复候选占用 0.27.3：MiMo WEB 历史必须保留带有工具调用或空 `tool_calls` 的 assistant 正文、每个调用的 ID；Responses `input_items` 读取将历史 easy message 投影为标准内容块，并给消息及 function/custom 调用和结果补齐缺省 completed 状态，保留 ID、分页、媒体、annotations、phase、显式状态和原始续接数据，不迁移或重写存储。兼容影响仅为纠正资源返回格式；默认历史、权限和客户端工具执行边界保持。

真实 MiMoML 输出已证明：schema 声明 string 的 `batch_number=11733` 在 WEB 中以无类型文本返回，旧解码器误读为 JSON 数字。候选按声明的 string/nullable string 类型映射 MiMoML、XML 和 key=value 文本参数；明确的 JSON 参数对象不做类型修复。支持既有局部 `$ref`/`anyOf` 类型查询并限制展开成本，严格 schema 校验及错误事件仍由统一协议层负责。

## 范围

### 0.27.4 上线验收发现的回放与传输修复

- 目标：0.27.3 七家完整 SDK 首轮 47/49；GLM 首帧前 `provider_transport_error` 的原请求单独重试通过，Grok 原请求及单独重试均遗漏技能要求的业务字段。保留原失败，完成修复及差量验收后发布 0.27.4。
- 范围：Grok WEB 完整历史的角色、历史结束与下一 assistant 回复边界；共享内部 HTTP 连接池的空闲连接回收与 Spring 生命周期。已核实 Automation Uvicorn 0.51.0 默认 keep-alive 为 5s，当前客户端 max idle 为 30s；配置不匹配属于已知风险，不能将其直接认定为这次提前关闭的唯一根因。
- 非目标：不裁剪或改写历史、技能、工具定义和结果；不在提示词注入测试答案、不改 strict 校验、公开契约、重试策略、账号/Key、WEB 限制或 DB/Redis 部署。
- 影响文件：Grok Python WEB builder 与 Java request mapper、`WebClientConfiguration`、对应回归测试、统一版本、接入说明及本报告。
- 验收：单用户普通提示保持；多角色与完整函数回放顺序、call_id、正文和参数无损；真实 Grok WEB 对失败合成请求的候选回放按原技能返回所有字段。客户端主动回收闲置连接，Spring 关闭释放连接池。七家已有完整证据保留，候选完成相关本地门禁和真实差量后才推送生产，再在新版本核验七家与资源接口。
- 测试：Grok/容器/历史 Python 回归、Grok mapper Java 与实际本地 HTTP 连接复用/回收测试，Backend test/bootJar、Automation pytest/ruff、Web lint/build、版本/JAR 校验；使用现有账号在隔离进程验证候选 WEB，不持久化凭据补丁。生产记录 CI、GitOps、Pod、SDK、普通 INFERENCE 账本、缓存控制差量、最终模型 guard。

### 0.27.5 Grok 单回答和原生终态修复

- 目标：0.27.4 Grok 真实 Chat 结果和 Responses state 仍各一次遗漏，不能判全家验收完成。确认 builder 在 none 时清空 tools 并重新开启 `enableSideBySide`，Java mapper 也默认开启；Gateway 解码器忽略 `response.done.response.status`。修复这两处契约缺陷，并继续用原失败合成请求做原生字段对照，不能仅以 HTTP 200 计通过。
- 范围/影响：Grok WEB Python/Java request mapper、Gateway event decoder 与回归、统一版本；保留原输入、历史/工具语义、已有 SSE、媒体及状态所有权。单个公开推理不启用厂家比较模式；Gateway 只有明确 completed 才进入成功，非 completed 或缺失终帧失败，legacy 流保持原行为。
- 原生错误：`response.grok.output.output.stream_error` 按实际 kind/message 解码；已证实的 `global_rate_limit` 映射为 `upstream_unavailable`，保留 code/channel/provider scope，沿用既有前输出重试边界，不触发账号冷却或认证恢复。账号配额错误保持原 `rate_limited` 分类。不新增重试策略。
- 非目标：不增加公开 API 或协议，不改严格参数或测试答案，不改重试/凭据/账号，不将历史模型语义遗漏断言为双回答的唯一后果。未经实测的 system 参数不直接进入生产。
- 验收/测试：none/auto/required 都不重新打开双回答，完整原输入不变；原生 incomplete/failed/未知/缺终帧不能转成 completed，已输出片段保留且没有成功 usage/state。相关 Java/Python 回归及完整门禁；原合成失败 WEB 对照通过后才发布，最后验证新版本七家完整 SDK 与资源清理。
- 发布前证据：2026-10-05 隔离候选连接真实 Grok WEB 的官方 SDK 七项全部通过，覆盖完整历史、strict 函数、结果语义、Gateway state/resource schema/清理；候选本地 Backend 513 passed / 5 skipped、Automation 525 passed、Web lint/build、ruff、八处版本/JAR 校验通过。`session.instructions` 虽回显但未影响回答，独立 system item 未见生效，不写入生产映射。

### 0.27.7 Grok 模型账号资格与禁用工具修正

- 目标：修正 Grok 账号等级与模型目录资格不一致，以及 Runtime 未识别对象形式 `tool_choice={"type":"none"}`；不把模型语义偶发成功认定为完整 Agent 验收。
- 范围/文件：`InferenceProvider` 的可选 `ModelAccountPolicy`、Grok Provider 复用现有等级规则、`ModelCatalogCache`、Java/真实 PostgreSQL 回归；账号 metadata 的不可变快照保留 JSON null；Python builder 与 none 形式回归、统一版本、接入及发布记录。
- 非目标：不修改账号等级、购买订阅、替换客户端所选模式、Key、DB 结构、重试、历史内容、Gateway 状态或其他厂商策略；不采用未通过的 JSON 分区格式。
- 验收：basic 不被目录计入 SUPER/HEAVY 模式；别名及媒体也遵循同一资格规则，合格账号的模型冷却/配额计数准确，零可用账号时明确 UNAVAILABLE；未知 Grok 模型不能导致全目录读取失败。无专属策略的厂商保留原目录语义；禁用工具的两种形式均清理工具定义，保留原有正文格式。完整 Agent 门禁未通过前不推送 main。
- 测试：真实 PostgreSQL 覆盖 basic/混合等级、冷却/过期/禁用、null/未知等级、模型别名/未知模型、媒体、其他厂商及缓存；有合格限制账号时冷加载固定 3 次批量查询，空限制账号 2 次，无限制策略 1 次，热缓存无新增 SQL。Java/Python 相关及全量门禁、版本/JAR 校验；生产只读核对账号等级和现有模型详情。缓存 namespace 升为 v8 隔离旧资格结果。
- 已撤回实验：当前轮次分区 JSON、完整 JSON 和额外 none 提示虽有单次成功，两轮真实 SDK 仍有函数/结果遗漏；原生多角色/多 user item 与 keep_context 三组对照没有保留早先历史。这些实验均不进入候选代码，保留完整输入及失败证据，不将有限成功样本当作稳定性保证。

### 0.27.8 目录资格校验读取成本与 Grok 协议调查

- 目标：消除 0.27.7 账号资格校验引入的两次冷缓存数据库往返，继续定位 Grok 完整历史/函数回放的真实 WEB 入口。
- 范围/文件：`ModelCatalogCache` 在同一 SQL 快照内分别聚合限制厂商的账号与模型冷却，仅随首行传输；保留现有模型/滚动健康/探测查询及策略判断。真实 PostgreSQL 回归、统一版本、当前任务与证据报告；Grok 先调查官方静态前端和合成原生事件，仅采用有正向语义证据的字段。
- 非目标：不改变公开 API、账号等级、权限、冷却语义、TTL、DB 结构、历史正文、工具执行边界或用户所选模型，不扩展 Qwen，不增加重试次数。
- 验收：限制/不限策略、混合等级、空账号、空目录均冷加载一次 SQL、热缓存零 SQL；账号及模型冷却来自同一数据库快照，资格数据不随每个模型重复传输；目录字段及资格结果保持。Grok 未通过完整 Agent 验收时候选不推送 main。
- 测试：真实 PostgreSQL 资格/配额/空值/缓存/首行与空目录验证、只读生产 SQL 结果差量及执行计划；Backend test/bootJar、Automation pytest/ruff、Web lint/build、版本/JAR 校验。合成 WEB 调查保留失败，不持久化会话凭据补丁。

### 0.27.9 Grok 原生系统提供上下文桥接候选

- 目标：修复完整历史混在当前 user 正文造成的历史回答/函数结果遗漏。0.27.8 调查确认官方 WEB `buildWireInputChunks` 使用 `systemProvidedContext`，`encodeInputChunk` 按 proto 字段名编码；原生 `system_provided_context` 的系统口令、40 轮历史批次和原失败函数结果三项均正向生效，inline 对照通过。
- 范围/文件：Grok Java/Python 构造器将当前最后一个有正文的 user 及之后的调用/结果保留在当前输入，前缀完整消息无损 compact JSON 放入 `systemProvidedContext`；Gateway 发送一次带 item 的 `response.create`，输入块映射为 `system_provided_context`。当前工具契约、strict 校验、调用方执行和错误终态保持。相关回归、统一版本与发布证据。
- 非目标：不把 WEB 系统提供上下文宣称为 OpenAI 原生角色优先级，不映射未验收的原生 clientToolResult/MCP，不缩短/重复历史，不改变付费等级、所选模型、Gateway store/continuation 和重试预算。
- 验收/测试：Java/Python 顺序、完整 system/developer/skill/所有历史与工具 ID 保留，两种 none、单 user、无 user 及空白后续保持；真实原生正向证据之后仍需官方 SDK 七项完整通过，保留所有失败。相关/全量 Backend/Automation/Web/版本门禁；满足七厂商门禁前不推送 main。

### 0.27.11 关键租约与缓存连接隔离

- 目标：消除非关键缓存命令对账号获取/续租/释放的连接队头阻塞，并提供可定位的内部故障日志。
- 范围：`AccountLeaseRedisClient` 复制现有 Redis/Lettuce 配置、拥有独立连接生命周期；`AccountLeaseService` 通过它执行原 Lua，readiness 并行检查缓存与租约两条连接。
- 非目标：不迁移 Redis/PV、不修改 3s/250ms 预算、账号容量/fencing/TTL、API/DB/Key/厂商重试；独立连接不能解决 Redis 服务或底层网络整体不可用。
- 验收：真实 TCP fixture 阻塞缓存响应并令原共享 Lua 超时，独立租约仍完成；关闭资源、连接故障/超时失败封闭、配置继承、两条 readiness 的失败路径正确。对外继续使用既有 `coordination_unavailable`。
- 验证：Backend 相关与全量测试/bootJar，Automation/Web/版本契约；保留生产 0.27.6 发布前六家 31/42 的五次协调失败与衍生跳过。七家已取得的厂商语义证据、候选隔离证据和发布后真实协调/账本证据分别记录，不将一次正向连接对照当作 3s 根因已关闭。

### 0.27.10 Grok 状态资源元数据消费与历史调用身份一致性

- 目标：0.27.9 正向原生字段通过，但隔离 SDK 首轮 6/7、state 汇报失败，候选不发布。真实 WEB 输入证明手工与 state 当前文本一致，历史仅增加 Gateway message ID；原 JSON 上下文把这些非语义资源 ID 送入模型，造成相同正文的上下文不同。Java formatter 同时遗漏 assistant function ID、重复 tool 正文，与 Python 不一致。
- 范围：仅在 Grok native context 表示中消费普通 role message 的顶层资源 `id`，原始 Gateway 状态、输入对象、正文、顺序、嵌套 function ID、call_id/tool_call_id 及其他字段保留；非 message 类型身份保持。Java 当前轮历史采用完整 tool_calls JSON，tool 正文只输出一次，两端保持一致；继承 0.27.8 单 SQL 目录优化和 0.27.9 已证明的 native context 字段。
- 验收：相同业务内容的手工/状态桥接当前输入与 context 完全一致，资源查询的身份和权限不变；call/result 对应关系完整、原对象未变。相关及完整门禁、版本/JAR、候选真实 SDK 七项、其余六家复核后才发布。
- 边界：`quoted_text` 历史与原生 `client_tool_result` 虽被接受/回显，结果语义未通过，不进入正式映射；不将 formatter/资源元数据修复认定为全部模型语义稳定，候选完整失败仍须保留。

### 0.27.6 跨通道尝试账本修复

- 目标：0.27.4 DeepSeek 的 API 失败后 Runtime 生成成功，但 fallback 将 attempt 重置为 1，与 `usage_events(request_id, attempt)` 唯一约束冲突，成功记录被 `ON CONFLICT DO NOTHING` 丢弃。修正单请求跨通道的记录编号。
- 范围/影响：`InferenceCoordinator` 及真实 PostgreSQL/完整协调器回归、统一版本与发布记录。分离现有通道内重试计数与全请求遥测尝试编号，后者严格递增；保留 API→Runtime fallback、重试预算、有效输出后不重试、账号排除及释放、PROBE/INFERENCE 边界。
- 非目标：不改 API、数据库结构、重试条件或次数，不回填缺失的历史成功事件，不根据 SDK 输出伪造账本。0.27.5 CI 三项质量通过但镜像发布阶段已主动取消，GitOps 未更新；不复用或覆盖其版本和可能的部分制品。
- 验收/测试：JSON/SSE 都覆盖 API 失败→Runtime 成功，以及 Runtime 两次失败后第三次成功；真实 PG 保留每次失败和成功，attempt 为 1..N，原本 Runtime 三次预算保持。完整 Backend/Automation/Web/版本/JAR 门禁，通过后发布 0.27.6 并验证七家 SDK 与普通推理账本。

- Arena、MiMo、DeepSeek、LongCat、GLM、Grok Web、MiniMax：逐项核对厂商 WEB 构造字段与官方页面/运行时证据，使用合成内容、单参数变更和有限推理验证。Qwen 沿用此前排除范围，仅检查代码，真实能力标为未验证。
- 覆盖 temperature、top_p、三个输出 token 上限别名、reasoning/thinking、search、function 控制，以及平台负责的 store/continuation/SSE。区分字段被接受、实际转发、开关映射、网关模拟和不支持；HTTP 200 不作为参数生效的充分证据。
- 不把客户端填的 1M/128K 或官方付费 API 规格当成 WEB 上限；不默默删除有约束意义的参数。不确定的上限保持未知并给出明确错误。未提供/null 的可选字段不应触发伪兼容错误。
- 修正已证实的误声明、忽略参数和长输入拒绝被包装为成功；输入和工具内容不得静默裁剪或省略。厂商不能满足的功能保留明确拒绝与可读能力说明。
- Xiaomi MiMo 桌面端只读取本机安装版本、发送逻辑及脱敏请求组成，定位 system/tools 的自动注入与重复。不得修改客户端凭据、模型配置、会话、历史或安装制品。
- 按用户后续要求继续定位各家 WEB 输入限制：区分前端字符/字节限制、服务端实际接受边界、模型 token 上下文；有来源的字符限制独立展示，禁止换算成虚假的 token 规格。未知上限保持未知，有限合成探测遇到拒绝/超时即停止增大输入。
- 核对原生 WEB 的 tools/functions/skills/system 入口：只使用合成 schema 和唯一标记，区分字段接受、被忽略、真正的结构化调用与专用 Agent 执行。只有已证明调用/返回/续接语义的字段才进入生产映射；不能用重新放置字段的方式丢失 system 或工具定义。

## 0.27.12：Grok native input chunk 时序修复

- 目标：修复 0.27.11 完整 SDK 中两次手工工具结果回放遗漏；保持 OpenAI Chat/Responses、none 策略和完整历史契约。
- 范围：将 Grok WEB `response.create.item.x_grok.input_chunks` 中完整 `system_provided_context` 放在当前 `text` 之前；无 context 时仍只有 text。Java API 与 Python Runtime 两条发送路径同步。
- 非目标：不修改 caller system/skill、工具 schema、正文、顺序、call_id、stored state、账号选择或重试，不新增托管工具、历史截断/压缩、native 角色强制优先级声明。
- 影响文件：`GrokWebGatewayChat`、`grok_web_browser.py`、真实发帧/Java transport 回归、统一版本与当前报告/接入说明。
- 验收：原顺序对照失败保留；固定输入、既有失败账号的两种顺序对照；空/nonempty context 与父响应/EOF/错误回归；新不可变 0.27.12 完整协调器七家官方 SDK、INFERENCE 账本通过后提升同一四镜像。
- 测试：先以新发帧期望复现旧实现失败，再运行 Backend/Automation 全量、Web lint/build、ruff/版本/JAR；按既有 deploy=false/GitOps 路径验收和发布。0.27.11 未发布，不复用其版本或覆盖镜像。

### 0.27.13：补齐已有 WEB 直连 WebSocket 转发

- 目标：修复 0.27.12 完整候选中 Responses 手工/state 回放仍失败的跨通道缺漏；调用方继续只用 OpenAI Chat/Responses。
- 范围：Grok 内部 API 通道的 `_chat_stream` 仍只发送当前 text，丢弃已拆出的 native history，并使用旧的 item.create + response.create。改为与已验证 Runtime 一致的一次带 item 的 response.create；完整 context 在前、当前 text 在后，parent_response_id 属于 response.create。
- 影响文件：`grok_web_browser.py` 的共享 `inputChunks` 构造、`grok_web_api_actions.py`、两个通道真实发帧/VM 回归、统一版本和当前证据。沿用现有内部传输选择，不扩展厂商官方 API。
- 非目标：不改变客户端协议、系统/技能/工具正文、历史/身份、none、账号/Key、通道选择、重试或终态；不将隔离 Runtime 证据替代完整 AUTO 路由验收。
- 验收/测试：先在旧直连实现复现 frame/context/parent 断言失败；覆盖空历史、有 system/skill/早期历史及工具结果、父会话/父响应和源对象不变，两个 Python 发送路径共用有序 chunks；质量/版本门禁后，新四镜像七家完整 SDK 与普通账本通过再发布。0.27.12 的失败原样保留、版本不复用。

### 0.27.14：明确已分区的 Grok 当前轮范围

- 目标：0.27.13 七家矩阵仍为 47/49、Grok Chat/state 回放遗漏。实际失败均为 Browser Runtime 单次成功，不能把已修正的直连路径缺漏当作该语义失败的已证实原因。
- 范围/影响：历史已放入独立 native context，而当前工具结果片段仍声明为 complete conversation；在 Python `_prompt` / Java `GrokWebRequestMapper` 中显式区分完整对话与当前轮，仅分区后有多条当前消息时使用 Current turn 说明。保持原 JSON context、chunk 顺序、全部角色/字段/工具 ID/正文、none、源对象和状态资源不变。
- 非目标：不采用对照中失败的 history+current 双说明，不增强 caller skill/none，不删历史、不变更重试/账号/Key，不将原始映射一次正向复测当作稳定性修复。
- 验收/测试：旧实现先在 Java/Python 多轮工具回放回归中失败；无 history/no user/单用户路径保持。固定失败输入和两账号交叉对照保留 baseline 1/2、current-only 2/2、history-only 2/2、combined 0/2，仅选择修正范围矛盾的 current-only。完整质量、版本与新四镜像七家 SDK/普通账本通过后才能发布，有限样本不作原生角色优先级或全账号稳定性保证。

### 0.27.15：分离 Grok 指令上下文与历史

- 目标：0.27.14 候选 49/49，但生产复测 48/49；Grok Chat 工具结果回放的原生完成响应仍重复历史确认语句，不能报告为完整验收通过。
- 范围/影响文件：Python `grok_web_browser._conversation_input/_prompt` 与 Java `GrokWebRequestMapper` 同步；仅把开头连续的普通 system/developer 消息放入 native context，其他早期历史以完整 JSON 放在当前轮文本前。沿用原 chunk 顺序、工具选择、身份消费规则与原始存储；相关 Java/Python 与真实发送回归及统一版本文件同步。
- 非目标：不删减或压缩正文/字段，不重复系统指令，不修改 caller/技能/工具结果，不按账号切换顺序，不改变账号、Key、重试、错误或 API 契约。
- 验收：旧实现先复现分区断言失败；完整历史/嵌套调用 ID/附加字段、Gateway message ID 消费、非开头指令原位、无 user/空白 follow-up/无历史、源对象不变均覆盖。修正后质量/版本、新四镜像七家完整 SDK 和普通账本通过，再提升同一制品并复核生产；保留 0.27.14 的生产失败。
- 原生依据：相同失败输入在三账号上，原映射 2/3、反序 1/2、仅指令 context 5/5（context-first 3/3、context-last 2/2）；JSON 当前轮、当前区前置指令、单 transcript 均未解决原账号失败。选择分区修正，保持现有 context-first 时序；有限样本不作全账号或原生 system 优先级保证。
- 验收结果：Source `08b0717`、CI `37487198470` 全部质量及四镜像通过；候选/生产各七家 **49/49**，普通账本分别 **35/37、35/36**，失败尝试保留。自有资源清理后 GitOps `3930c78` 提升同一四镜像，四组件版本/digest/Ready、公开健康及模型 guard 完成校验；本轮功能门禁完成，性能和扩大样本仍按[发布报告](../reports/WEB_PARAMETERS_AND_CONTEXT_2026-10-04.md)记录。

## 非目标

不扩展全协议、托管工具、本机工具执行或厂商音视频；不新增账号、不轮换 Key、不处理 Qwen 账号、不迁移 DB/Redis/PV，不发布 Cloudflare Worker。

## 影响文件

现有 Provider/协议声明与校验、ModelCapabilityContract/ModelCatalogCache、MiMo 事件解码与请求构造、对应 Java/Python 测试、兼容性探测脚本、统一版本文件、API/接入说明和本轮验证报告。

## 验收与测试

0.27.0 上线差量发现 `reasoning:null` 被重复 shape 校验拒绝、null 控制项越过语义边界触发 Arena 422；后续修复占用 0.27.1，覆盖 nullable reasoning/stream_options、控制项 null 省略及显式 false 保留。MiMo 先于 SSE 返回 JSON 400 为既定契约，修正探测脚本的 HTTP 200-only 误判，并补充首事件失败回归测试。

最终收尾候选 0.27.2 修正 LongCat 说明中的真实 WEB camelCase 目标与 catalog v7；同时修正精确生成缓存遗漏 raw WEB 控制项的问题：未纳入 cache key 的非空控制必须绕过读写缓存，普通 canonical generation 按原 key 内容隔离，null 仍视为缺省。prompt key v3 避免重用先前可能混入不同控制语义的旧条目。覆盖 controlled 请求不会读到/污染 plain 缓存、显式 false、普通缓存与 sampling 隔离；上线用 LongCat 常用差量及 Arena 不同 search 控制的后台尝试验证。

- 参数表须注明源字段、WEB 目标字段、限制/映射方式和证据级别；未实测组合保持未验证。逐厂商至少完成 Chat/Responses 基础控制与可用参数现场检查，保留失败和后台尝试。
- 覆盖缺省/null、非法类型/范围、别名冲突、不支持参数、已提交 SSE/非流式错误；能力声明与实际校验及构造行为一致。
- MiMo 短输入及历史工具闭环保留；长输入拒绝有单一失败终态和非重试错误，不误判凭据、不打开模型熔断、不记录生成成功。完整默认历史和原始媒体内容保留。
- Xiaomi 桌面证据仅记录结构/数量/大小、版本和公开实现位置，不保存用户全文或秘密。说明是否客户端主动注入、是否网关增加，以及重复比例的验证范围。
- Backend 测试/bootJar、Automation pytest/ruff、Web lint/build、版本契约；候选提交后跟踪 CI/GitOps/Pod/API，再用既有 Key 进行有限现场差量验收。
- 发布前逐家验证 Chat/Responses 的完整 system、developer 中的合成技能、超过 32 条的默认历史、strict function 参数、call_id 关联、客户端工具结果、JSON/SSE 和网关 continuation。使用无副作用的业务数据，模型必须从不同角色和早期历史提取参数，再按技能要求汇报工具结果；保留请求 ID、失败、实际后台尝试和生产版本。基线现场结果与候选本地证据分别记录，不能把参数 41/41 当成完整 Agent 验收。

## 兼容与回滚

沿用 Provider/Action/Runtime 边界，不新增平行协议。新增能力字段可兼容现有客户端；对原先被忽略或误报成功的参数明确拒绝，记录具体影响。无数据/凭据迁移；0.27.3 发布后可回滚至已经部署的 0.27.1 四组件不可变镜像，保留原租约/权限/历史，但会恢复已记录的参数类型、资源格式及缓存缺陷。整轮变更之前的历史基线是 0.26.3。
