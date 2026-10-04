# 厂商 WEB 参数取证与适配

## 目标与授权

以 0.26.3 为基线，按用户批准逐厂商探测并修正 Chat/Responses 到 WEB 的参数、输入边界和错误桥接。新增可查询的参数适配说明属于兼容能力扩展，首个部署候选占用 0.27.0；候选部署后再修改源码必须另占版本。沿用已有提交、GitOps 部署及现有分发 Key 测试授权。

## 范围

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

## 兼容与回滚

沿用 Provider/Action/Runtime 边界，不新增平行协议。新增能力字段可兼容现有客户端；对原先被忽略或误报成功的参数明确拒绝，记录具体影响。无数据/凭据迁移；回滚至 0.26.3 四组件不可变镜像，撤回本轮参数说明与修复，保留原租约/权限/历史。
