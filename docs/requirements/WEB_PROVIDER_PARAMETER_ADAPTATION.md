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

- Arena、MiMo、DeepSeek、LongCat、GLM、Grok Web、MiniMax：逐项核对厂商 WEB 构造字段与官方页面/运行时证据，使用合成内容、单参数变更和有限推理验证。Qwen 沿用此前排除范围，仅检查代码，真实能力标为未验证。
- 覆盖 temperature、top_p、三个输出 token 上限别名、reasoning/thinking、search、function 控制，以及平台负责的 store/continuation/SSE。区分字段被接受、实际转发、开关映射、网关模拟和不支持；HTTP 200 不作为参数生效的充分证据。
- 不把客户端填的 1M/128K 或官方付费 API 规格当成 WEB 上限；不默默删除有约束意义的参数。不确定的上限保持未知并给出明确错误。未提供/null 的可选字段不应触发伪兼容错误。
- 修正已证实的误声明、忽略参数和长输入拒绝被包装为成功；输入和工具内容不得静默裁剪或省略。厂商不能满足的功能保留明确拒绝与可读能力说明。
- Xiaomi MiMo 桌面端只读取本机安装版本、发送逻辑及脱敏请求组成，定位 system/tools 的自动注入与重复。不得修改客户端凭据、模型配置、会话、历史或安装制品。
- 按用户后续要求继续定位各家 WEB 输入限制：区分前端字符/字节限制、服务端实际接受边界、模型 token 上下文；有来源的字符限制独立展示，禁止换算成虚假的 token 规格。未知上限保持未知，有限合成探测遇到拒绝/超时即停止增大输入。
- 核对原生 WEB 的 tools/functions/skills/system 入口：只使用合成 schema 和唯一标记，区分字段接受、被忽略、真正的结构化调用与专用 Agent 执行。只有已证明调用/返回/续接语义的字段才进入生产映射；不能用重新放置字段的方式丢失 system 或工具定义。

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
