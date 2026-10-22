# Arena 系统完整流程与 1.6.0～1.6.6 候选

## 当前结论

用户 2026-10-19 21:07 授权“自行注册、自行识别、自行测试”，随后明确要求系统自身完整流程。2026-10-22 10:35 完整只读校验生产四组件均为 1.5.16、版本/digest 匹配、当前 Pod Ready/0 重启、Argo Synced/Healthy；另有一次历史 shared automation 临时磁盘驱逐。1.6.3 的 Cookie 修复已通过两账号冷恢复及实际网络携带检查。1.6.4 首账号官方 v2 solved、真实输出；1.6.5 该账号冷恢复后连续两条成功、只创建一个浏览器，但另两个账号仍耗尽图块预算。逐格现场确认局部替换导致未变化答案丢弃，以及 opacity=0.01 被错误视为就绪；1.6.6 针对这两点修复。尚未提升生产或宣称整体恢复。

此前将 Windows UI 的验证码逐次确认规则扩展到 Linux/Camoufox 是错误解释，已纠正。服务器流程沿用用户当前授权、有限请求及失败停止条件，不重复等待确认。

## 系统完整流程实际结果

| 阶段 | 当前证据 | 判定 |
| --- | --- | --- |
| 创建注册任务 | 现有 `POST /api/admin/v1/registration-jobs`，任务 `84beeb72-d2fe-4e71-818e-3d6e6c9ddf61`；target/maxAttempts/concurrency 均 1，REQUIRED_POOL、有窗口、内部 GLM、tempmail_plus | 任务已执行并终结 |
| 邮箱与注册 | `mailbox_created → browser_launched → form_ready → form_submitted → upstream_accepted → otp_received` | 邮件已收到，不能据此宣称注册完成 |
| 设置密码 | 生产任务及隔离 1.5.21 各复现 HTTP 400；隔离结果有 token，官方返回 `Error registering email` | 未完成；没有导入可用账号 |
| 协议对照 | 当前官方密码页面 JS 使用 `/nextjs-api/auth/set-password` 和 `password`、`token`，与本项目相符 | 没有证据支持盲改字段；继续检查邮箱及上游状态 |
| 图块处理 | 原系统只点击 checkbox；1.6.0 新增图块识别、选择、提交及状态保护 | 本地回归通过，等待不可变容器与真实验证 |
| 账号保存/一次初检/桥接 | 新注册尚未达到保存条件 | 未验收，不能将认证或任务状态等同于真实输出 |

生产任务约 51.5 秒结束，关联 `c07e89b5-f4b7-444c-bf57-025d7ba16964`；一次任务内部的不同邮箱尝试属于已有 Provider 行为，并非新增循环任务。没有重置已有账号的一次初检预算。

隔离注册 Job `any2api-arena-register-20261019132738` 使用 1.5.21、Camoufox、原 Self Proxy 池及现有 Provider 注册函数，0 生成，已删除，浏览器/代理归零。含响应详情的证据使用派生密钥 AES-GCM 加密保存在服务器管理 private 目录，不提交 Git，不输出邮箱、密码、Cookie、验证码链接或主密钥。

## 1.6.0 Changelog

| 分类 | 关键文件/模块 | 行为变化 |
| --- | --- | --- |
| 图块闭环 | `automation/any2api_automation/providers/arena_challenge.py` | 只截图验证码 payload；支持 3×3/4×4，按当前选中集计算差量，复核后原生提交，等待官方页面更新 |
| 状态保护 | 同上 | 灰化、禁用、图片未加载/动态替换时不识别；AI 返回后检查截图一致性；换图停止使用旧答案；相同图不重复调用 |
| 调用预算 | `captcha/registry.py` | 严格 JSON/字段/整数/范围/去重，图块每次只允许 1 个网络请求；每次流程最多 3 次识别、每次最多 20 秒，取消继续传播 |
| 内部模型与递归保护 | `arena_challenge.py`、`arena_settings.py` | 限次耗时对照后默认 `mimo/mimo-v2.6-pro`，仅内部调用，可配置 `ANY2API_AUTOMATION_ARENA_CAPTCHA_AI_INTERNAL_MODEL`；拒绝 Arena 和未限定厂商/随机模型，避免递归占用 Arena |
| 页面生命周期 | `providers/arena_browser.py`、`arena_settings.py` | 沿用现有流的 required 事件、checkbox 和官方 Token callback；默认 v2 窗口 30→90 秒，整个处理受同一 deadline 和流取消管理 |
| 版本 | Backend/Web/Automation 与锁文件 | 新能力占用 1.6.0；已构建的 1.5.21 保留 |
| 运维与文档 | 恢复规格、本报告、当前任务板 | 使用系统注册 API 测完整链路，诊断脚本仅定位；明确历史授权范围、失败证据和发布门禁 |

不新增公开 API、数据库字段或周期探活，不改变下游 OpenAI Chat/Responses 契约、其他厂商行为、四节点绑定与 DeepSeek/Qwen 停用状态。图块窗关闭、AI 返回或按钮提交都不是通过证明，成功仍须官方 callback 后的真实正文与完成事件。

## 验证与运行态

- 最终 Automation 全量 773 passed / 3 Redis 条件跳过；图块定向 29 passed。Ruff/150 文件格式通过。
- Backend `test` 复用有效缓存，1.6.0 bootJar 重新构建成功；Web lint、TypeScript 与 build 成功。源码与 JAR 版本契约通过。
- 私有会话 `any2api-arena-operator-20261019125557` 最终 1 生成、0 有效输出；新增内部 AI 调用 0。原图块已消失，随后出现 Cloudflare 页面重新验证，不能把不存在的新题送 AI。现场已关闭、Job 删除、private access 标记 inactive，浏览器预算与代理引用归零。
- 新镜像/CI、隔离容器、真实验证码、账号保存、Chat/Responses 和恢复复用验收继续回写；尚未通过生产发布门禁。

### 不可变制品与 Camoufox 容器

- 源码 `3671b9e8fbb768b5f5ee606250e4c21a55cceff4`；[CI 39261933967](https://github.com/Prodigalgal/any2api/actions/runs/39261933967) 全部成功，Automation **776 passed**，包含真实 Redis 条件验证；`deploy=false`，GitOps 更新已跳过。
- Arena 镜像 `automation-arena-20261019-v1.6.0-release-3671b9e8fbb768b5f5ee606250e4c21a55cceff4-run39261933967-1`，digest `sha256:46a8d4c58c2762affb122df54bad881b93ba5efe8c4635b06518ae6c4461205c`；七个组件不可变制品均已解析成功。
- 实际 1.6.0/Camoufox/humanize 容器 5/5 通过：3×3、4×4 正确差量选中与提交，非法结果不提交，灰化/AI 禁用时 0 识别；复用同一会话，0 厂家/AI 网络请求。首轮夹具遗漏 `account_operation`、0 项即报 LookupError，修正夹具后才取得上述结果，未修改产品或镜像。
- 容器结果 `backend/build/arena-grid-container-v160-20261019134824.json`；Job 已删除，浏览器/代理归零。真实系统 API 与官方图块仍单独验收。
- 21:43 只读核对：生产仍为 1.5.16，四组件版本/digest 匹配、Ready/0 重启，Argo Synced/Healthy，DeepSeek/Qwen 0 副本，没有提升候选。

### 内部 AI 限次真实对照

仅向系统内部接口发送历史验证码裁图，不发送账号、侧栏或历史对话，不操作官方题目，四次均为单网络请求：MiMo Pro 首轮 15.4 秒输出不合结构，第二轮 17.9 秒返回合法空选择；GLM 与 MiMo Flash 各约 20 秒读取超时。此样本支持使用 Pro 作为本次默认模型，但不足以证明准确率或长期延迟；严格拒绝错误输出，不补括号伪造 JSON。已修正无内容时保留原始 transport diagnostic，避免把读取超时误报为非法 JSON，并增加独立识别绝对 deadline。

另一次不同邮箱驱动注册 Job `any2api-arena-register-20261019132952` 仍止于密码设置 HTTP 400；过程中已有 Provider 回退邮箱逻辑生效，不能视为两个邮箱厂商均独立通过对照。Job 已删除、浏览器/代理归零、0 生成。

## 遗留与回滚（1.6.0 时点）

1. Arena 官方验证和注册设置密码 400 均未恢复；需分别核对，不能将任一局部修复宣称为全链路成功。
2. 图块真实视觉准确率、动态图片延迟和官方验证通过率尚未验收；保留有限预算，不进行无限注册或验证。
3. 当前无生产代码/配置部署，无数据库迁移；继续保留生产 1.5.16 不可变镜像组。候选失败时删除自有诊断 Job，账号未经一次初检不启用；不清空账号库或重置检查预算。

匿名注册的原有空 v3 Token 与官方网页不同，另做有限 POC 调用官方 `sign_up` SDK 后仍止于密码设置 400；该结果不能支持“补 Token 即可恢复”的结论。Job `any2api-arena-register-20261019134750` 已删除、0 生成、资源归零，没有将未证实修复写入产品。

## 1.6.0 真实系统 API 与失败等待缺陷

两条请求均通过实际 1.6.0 镜像中的 FastAPI 路由、认证依赖、Action 分发、Arena transport 和内部视觉 AI；诊断只添加生成/AI 硬上限和元数据采集。每条先核对预期身份及保存配置，原 affinity 严格绑定，未认证请求 401。内部 NDJSON HTTP 200 不代表生成成功，以下判定以帧内状态为准。

| 账号前缀 | 身份/配置 | 实际生成 POST | 内部 AI | 官方结果 | 整轮 |
| --- | --- | --- | --- | --- | --- |
| `28682cbe` | 匹配/完整恢复 | 1 | 2 次，分别 9.5/8.9 秒 | v2 required→timeout，403、0 正文 | 109.0 秒 |
| `030eb4e2` | 匹配/完整恢复，原 `.52` | 1 | 1 次 16.2 秒，输出结构非法 | helper recognition_invalid、0 提交；v2 最终 timeout，403、0 正文 | 109.2 秒 |

两 Job 均已删除，浏览器/代理归零，没有成功会话导出或生产账户回写。第二条明确证明 helper 已退出却仍等满验证窗口，不能继续将等待时间归为厂商生成耗时。

## 1.6.1 Changelog 与验证

| 分类 | 文件/模块 | 修复行为与兼容边界 |
| --- | --- | --- |
| 验证结果模型 | `providers/arena_challenge.py` | 显式 outcome/AI 调用/提交次数；区分已提交等待、widget 关闭和不可恢复失败，取消继续传播 |
| 有界重试 | 同上 | 非法结构后重新检查图像与可用状态，未点击前可在总 3 次内重试；已提交/灰化同图不重复识别 |
| 失败返回 | `providers/page_fetch_browser.py`、`providers/arena_browser.py` | 保留原 helper 兼容，增加携带 request_id 的局部 hook；失败仅结束该请求待定 challenge promise，释放 widget，不调用生成 AbortController.abort；已提交后继续等待官方结果 |
| 注册代理绑定 | `providers/arena.py` | 注册成功凭据保存实际 affinity 与 offset；重认证保留原凭据 offset 并随 credential_patch 返回。Java 推理/生命周期已有读取契约，不新增字段结构，不推测改写旧账号 |
| 统一版本 | Backend/Web/Automation 与锁文件 | 新候选占用 1.6.1，保留 1.6.0 不可变制品；锁文件依赖版本未变 |

- 定向 89 passed；全量 Automation **784 passed / 3 Redis 条件跳过**，Ruff/150 文件格式通过。Backend test 使用有效缓存，新 bootJar、Web lint/TypeScript/build、1.6.1 源码/JAR 版本契约通过。
- 回归包含两次非法后恢复、三次失败零点击、JS 验证提前结束/容器与计时器清理、已提交不取消生成，以及注册首轮/第二邮箱成功、重认证非零偏移和输入不变性。
- 官方原生密码表单另作单身份对照：Job `any2api-arena-register-20261019140424` 确实原生输入/提交，仍 HTTP 400 `Error registering email`，0 生成，已删除且资源归零，失败现场加密保存。前一诊断的辅助采集漏传参数，不能作为原生接口结果；已修正采集不影响产品流程后才取得此证据。
- function tool 图块 POC 单次读取超时，没有证明更好，不纳入产品。新候选 CI、实际容器与真实输出验收继续；生产仍为 1.5.16，未完成整体恢复。

## 2026-10-20：1.6.1 不可变验收

- 源码 `577ae868d34b611553a662b14b429273a86c3800`，[CI 39266235080](https://github.com/Prodigalgal/any2api/actions/runs/39266235080) 全成功，Automation **787 passed**，GitOps skipped。Arena digest `sha256:f97cfda5b3ff269103a0043bf6f4f5f451098145cf5944fa416f591bda7647e9`，七组件制品齐全。
- 实际 Camoufox/humanize 容器最终 **5/5**：3×3、4×4 正确选择提交；三次非法识别后只结束对应 challenge，不 abort 生成；灰化/禁用 0 AI，同 session 复用、资源归零。结果 `arena-grid-container-v161-20261020003854.json`。前两轮可交互夹具在 checkbox click 处超时、零 AI，不能抹去这两次失败；追加三个新 Camoufox 配置均通过，尚未确定间歇失败根因。

| 账号前缀 | 实际出口/身份/保存配置 | 生成/AI | 帧内结果 | 整轮 | 对比旧版 |
| --- | --- | --- | --- | --- | --- |
| `030eb4e2` | 原 `.52` / 匹配 / 精确恢复 | 1 / 1 | v2 failed、403、0 正文 | 44.8 秒 | 109.2 秒 |
| `28682cbe` | 原 `.214` / 匹配 / 精确恢复 | 1 / 1 | v2 failed、403、0 正文 | 47.7 秒 | 109.0 秒 |

两条均通过实际产品路由/认证/Action/transport，未认证接口 401；helper 在内部识别 20 秒截止后正确结束待定 widget。官方 Token、正文和完成事件仍缺失，没有成功凭据回写。Jobs 已删、浏览器/代理归零；验证码裁图及诊断加密保存，不能当作成功 session。

## 1.6.2 新发现与 Changelog

1. 后端遥测显示，两次内部 MiMo 识别 queue=0、账号获取约 0.1 秒、首包 12～16 秒，随后被 20 秒截止取消；另一次原始完整响应耗时 21.5 秒。20 秒限制会截断正常完成窗口；1.6.2 调整单次上限为 30 秒，整体 90 秒/最多 3 次/每次 1 个网络请求不变。
2. 当前官方 MiMo 页面 `main.f4f92c2a.chunk.js` 在有图片时设置搜索 disabled，项目却默认 enabled。固定账号、原代理、相同编号裁图的单次对照分别耗时 21.5/13.2 秒；只支持指出参数不一致，不能据此声称长期性能提升或重复尾段已解决。
3. 两次 MiMo 原始 SSE 均先返回完整 JSON，再追加重叠尾段；官方前端的 React 流同样按 content 累加，没有依据把尾段按字符串相似度删除。保留加密原始流，普通文本结构异常仍严格拒绝。请求关闭思考后仍有 reasoningContent，不能将客户端开关等同于上游确已关闭。
4. 编号裁图使用现有函数桥接，15.1 秒返回唯一 `select_grid` 及合法 `{"select":[7,8,9]}`。旧 POC 的 grid_invalid_json 是旧内容读取器未读取 tool_calls；与普通文本尾段异常分开记录。两次前置 POC 参数被网关 400 拒绝、未调用模型，核对本仓库契约后改用 `provider_options.mimo.web_search_status=disabled` 才取得该有效结果。GLM 45 秒、MiniMax Flash 20 秒有限对照超时，没有替换默认模型。

| 分类 | 文件/模块 | 行为与兼容性 |
| --- | --- | --- |
| 严格识别返回 | `captcha/registry.py` | 图块复用既有函数桥接，唯一指定函数/严格 schema/串行调用，参数再做 JSON、整数、范围和去重检查；不修剪非法正文，其他视觉方法保持内容响应 |
| 图像编号 | `providers/arena_challenge.py` | 从实际 DOM 位置取得单元格边界，仅在送 AI 的截图副本上标号；原始截图、网页、选中状态和换图一致性判定保持；无效几何停止识别 |
| 图片参数 | `providers/mimo_browser.py` | 图片、tool_choice=none 或显式关闭搜索时映射为 disabled；文本默认和有效显式搜索模式保持，输入消息不变 |
| 内部视觉请求 | `captcha/registry.py` | 非函数内部视觉请求明确 tool_choice=none；函数图块保持指定函数，外部视觉原有请求保持 |
| 截止与版本 | Arena/六处统一版本 | 单次 30 秒，整体预算不变；候选占用 1.6.2，依赖版本未改，不复用既有制品 |

定向 **151 项**通过；全量 **808 passed / 3 Redis 条件跳过**，Ruff/150 文件格式通过；Backend test 复用有效缓存、新 1.6.2 JAR、Web lint/TypeScript/build、源码/JAR 版本契约通过。新不可变容器和真实官方验证继续。没有 OpenAI API/数据库迁移或生产部署；完整注册的密码设置 400、间歇点击、MiMo 普通文本尾段和 Arena 官方通过率继续保留为风险。回滚仍是生产 1.5.16 不可变镜像组。

### 1.6.2 制品与补充失败证据

- 源码 `bc01f966c408bd38e74c9b69c22f5f4ee386e341`；[CI 39351684433](https://github.com/Prodigalgal/any2api/actions/runs/39351684433) 全部成功，七组件不可变制品已解析，`deploy=false`、GitOps skipped。Arena digest `sha256:7f3a3ed46788331e3801be8b8e1af17de66572235f2f6c8a4c9d2c1abd19bd31`。
- `arena-grid-product-v162-20261020010528.json` 将新 registry 模块接入仍为生产 1.5.16 的 MiMo worker，一次内部请求 30.5 秒读取超时；这既不是同版本完整验收，也不能用此前 15.1 秒的函数 POC 掩盖该失败。新候选的真实识别链路需单独验收。

## 2026-10-22：环境一致性与 1.6.3 Changelog

### 已证实的会话缺陷

证据 `backend/build/any2api-arena-cookie-030eb4e2-20261020011804.json`：固定 1.6.2 镜像，同账号原 `.52` 节点与保存 Camoufox 配置；认证身份匹配，官方 SDK 一次 `chat_submit` 返回 Token 元数据（不保存或复用 Token），0 模型生成、0 AI。SDK 后 `_GRECAPTCHA` 为 1 条，`credential_patch` 保存后为 0 条，过滤状态恢复仍为 0 条；直接恢复原始状态为 1 条且值一致。Job 已删除、浏览器/代理归零，完整敏感状态仅加密封存在服务器管理 private 目录，没有回写生产。

Google 将 `_GRECAPTCHA` 说明为用于风险分析的必要 Cookie，因此删除它会破坏同账号验证状态的持久化。[Google 官方说明](https://developers.google.com/recaptcha/docs/faq)。该证据尚不能证明是 403 的唯一原因，也没有 Arena 后端的 Google assessment score。v3 是官方正常无感评估机制；v2 追加验证与容器/会话/网络/站点策略均需排查，不能仅凭版本或弹窗反推具体分数。[v3 官方说明](https://developers.google.com/recaptcha/docs/v3)。

| 分类 | 关键文件/模块 | 行为变化与兼容性 |
| --- | --- | --- |
| 会话持久化修复 | `providers/arena_browser.py` | Arena 局部覆写 `filter_storage_state`，在已有 Arena Cookie/origin 过滤基础上，只额外保留同账号 `_GRECAPTCHA`、精确官方域名、`/recaptcha` 路径和 Secure；不扩大共享 Runtime 白名单 |
| 隔离和拒绝规则 | `tests/test_arena_storage_state.py` | 覆盖保存/读取、深拷贝、不同账号状态不合并、旧凭据缺失兼容，以及 NID/SID/错误域名/伪装后缀/错误路径/非布尔 Secure 拒绝；Google origin 仍排除 |
| 版本与制品 | Backend/Web/Automation 六处统一版本 | 新候选占用 1.6.3，依赖版本未变，没有 API/数据库迁移；1.6.2 及生产 1.5.16 镜像均保留 |

- 本地定向 **75 passed**，全量 Automation **827 passed / 3 Redis 条件跳过 / 1 既有 Starlette 弃用警告**；Ruff 与 151 文件格式检查通过。Backend test 有效缓存、新 bootJar、Web lint/TypeScript/build、1.6.3 源码/JAR 版本契约通过。
- 新不可变镜像、官方 SDK 冷恢复与有界真实生成继续验收；当前没有生产部署或生产账号修改。旧版本已删除的 Cookie 无法凭空恢复，只由该账号官方 SDK 后续执行重新产生，禁止跨账号移植。
- 遗留：Arena 真实正文/完成事件、完整注册设置密码 400、控件间歇超时、MiMo 文本尾段及内部视觉延迟。回滚点仍为生产 1.5.16 镜像组；隔离失败则删除自有 Job，不重置账号预算或批量换出口。

### 1.6.3 不可变容器与两账号真实结果

- 源码 `3e08809eebb6707edf6c2ffa8ca554fe7958eafe`，[CI 39711670339](https://github.com/Prodigalgal/any2api/actions/runs/39711670339) 全成功，Automation **830 passed**（含 Redis），七组件制品齐全，GitOps skipped；Arena digest `sha256:5ebd9bdcdcf1f4187cfaa7455e7e4dcf709c1879e9ae6c42ee0916b4179a5c5d`。
- 两账号原 `.52` / `.214` 节点，各两次官方 SDK、0 模型生成；浏览器关闭重建前后 Cookie 均为 1，身份/保存配置一致，Google anchor/reload 请求从首次无 Cookie 变为恢复后携带 Cookie。证据 `any2api-arena-cookie-030eb4e2-20261022024614.json`、`any2api-arena-cookie-28682cbe-20261022025006.json`；不保存或复用 Token，不跨账号合并。
- 原镜像离线 Camoufox/humanize 控件 **5/5**，同会话复用，0 外网/AI。首次夹具因镜像拉取超过 160 秒而未执行任何用例；扩大诊断启动窗口后以同一不可变镜像重测，结果 `arena-grid-container-v163-20261022024911.json`。两次结果均保留，不能将启动失败计为厂家失败。

| 账号前缀 | 认证/配置/出口 | 生成/AI/提交 | 官方结果 | 整轮 |
| --- | --- | --- | --- | --- |
| `030eb4e2` | 匹配 / 原配置 / `.52` | 1 / 0 / 0 | v2 required→failed、403、0 正文 | 26.6 秒 |
| `28682cbe` | 匹配 / 原配置 / `.214` | 1 / 0 / 0 | 同上，图块布局错误已加密采集 | 25.3 秒 |

这两次使用各自冷恢复检查后的状态；图块 helper 在内部 AI 前抛出 `ValueError`，不能归为模型识别错误或仅凭它反推 Google 分数。Job 均删除、浏览器/代理归零，未回写生产账号。

### 容器环境对照与历史范围

- 两账号页面均 focus=true、visible、webdriver=false。实际 Xvfb 1365×900，浏览器保留各自原屏幕/窗口配置；第二账号以同配置、零账号/零 SDK/零外网，比较 1365×900 与 5840×2160 的中心/右侧/底部可信点击，均 3/3、约 1.4～2.3 秒。没有依据扩大生产 Xvfb 或宣称其提高分数；仍保留原环境。
- `git log -S` 定位：通用 Cookie 过滤来自 `4bcfd5a`（0.4.0），Arena 的仅本站域名规则来自 `69a37f9`（0.15.0）。这是长期缺陷；当前证据尚不能将全部故障锁定为某次 1.x 更新。
- 10:35 生产运行态校验遇到启动日志已轮换，改从已部署 `/app/app.jar` 的 manifest 只读提取版本；并将历史 Failed Pod 与当前 Running Pod 分开校验、同时保留故障记录。证据 `production-v1516-verified-20261022.json`。
- shared automation 的旧 Pod 在 **2026-10-21 17:33** 因 `Pod ephemeral local storage usage exceeds the total limit of containers 2Gi` 被驱逐，新 Pod 已接替。当前 ephemeral 约 486MB，其中 rootfs 478MB、日志 7.6MB；15 个 Chromium 临时 profile 中 4 个被当前进程引用，11 个未引用约 379MB。尚未执行删除、扩大配额或修改清理器；这些数据需要按归属和关闭路径继续排查，不将所有未引用目录直接判作可删。

## 1.6.4 真实图块边界回归修复

- 第二账号现场的 CAPTCHA 裁图为 **386×520**，9 个单元格组成 **390px** 宽表格，左右各溢出 2 CSS px；`min_left=-0.005181347`、`max_right=1.005181347`。图片和数字边界加密保存，明文仅导出 CAPTCHA 区域供核对，未发送侧栏或对话内容。
- 这是 **1.6.2 新增编号校验处理不全**：原有严格检查把实际 Google 布局误判为异常，离线夹具未覆盖。新回归先复现 **4 failed / 8 passed**，失败为 3×3/4×4、1×/2× 截图的正常 2px 溢出；不是放宽 AI 输出结构或修剪非法 JSON。
- 修复仅在 `_GRID_STATE` 对不超过 2 CSS px 的边缘计算与截图的交集；编号顺序和原生 DOM 点击保持。超过 2px 的布局继续拒绝，所有原有灰化、动态换图、deadline、取消和 3 次 AI 上限保持；不修改 Xvfb、引擎、指纹、代理、OpenAI 或数据库。
- `providers/arena_challenge.py`、`tests/test_arena_grid_geometry.py` 及统一版本占用 **1.6.4**；定向 **78 passed**，全量 **839 passed / 3 Redis 条件跳过 / 1 既有弃用警告**，Ruff/152 文件格式、Backend test 有效缓存/新 bootJar、Web lint/TypeScript/build、源码/JAR 版本契约通过。不可变镜像及真实图块验证继续。当前不提升生产，回滚仍为 1.5.16 镜像组。

### 1.6.4 CI 与原镜像控件验收

- 源码 `bb70f34c3cc2da0761bb90d6950f52a9f9700942`，[CI 39714424338](https://github.com/Prodigalgal/any2api/actions/runs/39714424338) 全成功，Automation **842 passed**（含 Redis）；七组件制品已解析，GitOps skipped。Arena digest `sha256:69d33e378b89a742afb7724d6ddde20eaa9b510701bae8a985fef81aea1b9322`。
- 同一不可变 Camoufox/humanize 容器 **7/7**：普通 3×3/4×4、真实 2px 溢出的 3×3/4×4、三次非法后结束待定验证、灰化和禁用；同 session 复用，0 厂家/AI 网络，Job 删除且浏览器/代理归零。结果 `arena-grid-container-v164-20261022030712.json`。
- 官方有界验收串行执行以避免两个内部视觉请求互相排队；每账号最多 2 次生成 POST、3 次内部 AI。继续使用该账号已验证的冷恢复状态与原 affinity，真实结果另记，不将控件通过等同于 Google 验证通过。

### 1.6.4 官方验证与真实输出

| 账号 | 出口/身份/配置 | 生成/内部 AI | 官方验证与输出 | 整轮 |
| --- | --- | --- | --- | --- |
| `030eb4e2` | 原 `.52` / 匹配 / 原配置 | 2 POST（验证前/后）、1 AI 28.8 秒、1 提交 | v2 solved，状态 200、23 字、完成事件，无错误帧 | 61.4 秒 |
| `28682cbe` | 原 `.214` / 匹配 / 原配置 | 1 POST、2 AI 17.4/19.3 秒、0 提交 | 两次函数结果合法，但画面再次变化，`changed_during_recognition` 安全终止；403、0 字 | 67.4 秒 |

- 结果 `any2api-arena-system-030eb4e2-20261022030847.json` / `any2api-arena-system-28682cbe-20261022031311.json`。两账号均核对当前官方 v3 公钥与配置一致；未认证路由 401，全部 Job 删除、浏览器/代理归零。
- 首账号成功状态已 AES-GCM 加密保存到服务器管理 private，外层 `encrypted_state_saved=true`；诊断内层 `private_state_saved` 原初始化值未更新，不能用它否定外层已验证的加密输出。尚未导入生产。第二账号未标作成功或回写状态。
- 本轮证明完整图块 AI 路径可以获得 Google 官方通过及真实正文；不证明全部账号、每道动态题或生产 OpenAI 客户端已恢复。等待浏览器关闭后的恢复、同会话复用和 Chat/Responses 端到端验收。

## 1.6.5 识别期间换图恢复

- 首账号真实成功后，第二账号暴露了一条可恢复分支：画面在 AI 工作期间变化，旧答案必须丢弃，但原 helper 直接终止，仍有识别预算却未继续。先用三个定向回归复现旧行为失败，不能以再次抽到静态题的成功覆盖这条失败。
- `providers/arena_challenge.py` 现在区分关闭、尚未就绪与有效新图：关闭则结束；未就绪在原 deadline 内等待；有效新图重新识别。旧答案从不点击到新图，持续换图最多 3 次后停止，单次 30 秒/总 90 秒、图块校验、提交保护、取消传播均保持。
- `tests/test_arena_challenge.py` 保留原“旧答案零点击”断言，扩展为三次预算耗尽，并新增两次换图后成功、未就绪等待、等待中的 deadline/取消；定向 **90 passed**。统一版本占用 1.6.5，不新增 API/数据库字段或修改引擎、指纹、代理与初检预算。
- 本地全量 **843 passed / 3 Redis 条件跳过 / 1 既有 Starlette 弃用警告**；Ruff 与 152 文件格式检查、Backend test 有效缓存及新 1.6.5 bootJar、Web lint/TypeScript/build、源码/JAR 版本契约均通过。下一步使用新不可变镜像验证识别期间换图及真实成功状态的冷恢复、同会话复用；尚未提升生产。

### 1.6.5 CI、原镜像与真实复用

- 源码 `7e9ffd2a94c3018f3dca867c61b6320802864281`，[CI 39716903340](https://github.com/Prodigalgal/any2api/actions/runs/39716903340) 全成功，Automation **846 passed**（含 Redis），七组件制品齐全、GitOps skipped。Arena digest `sha256:f2932f48403456dd9b70ea931b83f39df2ef95db69c6e64dc58a06dbeddc1108`。
- 原镜像 Camoufox/humanize 实际控件 **10/10**：保留 7 项，新增两次换图后才使用当前答案、换图后未就绪等待、持续换图三次结束；前两项只点击最终答案，最后一项零点击。0 外网厂家/AI、同 session 复用，Job 删除、资源归零，证据 `arena-grid-container-v165-20261022033402.json`。

| 账号 | 实际出口 / 配置 | 请求与输出 | 内部 AI / 官方结果 | 整轮 |
| --- | --- | --- | --- | --- |
| `030eb4e2` | 原 `.52` / 匹配 | 冷恢复后两逻辑请求均成功，23/22 字及各自完成事件；共 3 POST，只建 1 个浏览器 | 第一次 0 AI、6.8 秒；第二次 1 AI 12.2 秒、v2 solved，35.7 秒，触发原因 `prompt_rate_limit` | 54.6 秒 |
| `28682cbe` | 原 `.214` / 匹配 | 首逻辑请求 403、0 字，停止后续请求，共 1 POST | 3 AI / 0 提交；首轮 502、后两轮结构合法 | 73.7 秒 |
| `ce1f10cf` | 按原账号 UUID affinity 的 `217.142.229.2` / 匹配 | 首逻辑请求 403、0 字，停止后续请求，共 1 POST | 3 AI 均合法，预算耗尽、0 提交 | 69.0 秒 |

- 对应 `any2api-arena-system-030eb4e2-20261022033550.json`、`...-28682cbe-20261022033813.json`、`...-ce1f10cf-20261022034200.json`。各自原配置、认证身份匹配、未认证 401，全部 Jobs 删除、资源归零；只有成功状态加密导出，未导入生产。
- `99814086-653e-491d-8276-b66bfbb11b06` 的内部 MiMo 502 为 `tool_call_generation_failed`：queue=0、账号获取 96ms、TTFB 7.9 秒、总 13.3 秒；其后两次均成功。保留失败，不按队列拥堵归因，不修剪非法正文伪造函数结果。
- 发布准备已在独立 GitOps clone 渲染 23 份资源，证明仅 5 个既有 image 引用变化，其他配置完全一致，DeepSeek/Qwen 仍停用；尚未执行主分支提升、GitOps 更新或凭据刷新。

## 1.6.6 动态题局部答案与渐显就绪

- 用未修改的 1.6.5 原镜像，对 `28682cbe` 做一轮最多 1 POST/3 AI 的逐格诊断：3 个合法函数结果，实际只点击 2 次、0 提交、403。`any2api-arena-system-28682cbe-20261022034644.json`；图片来源、逐格状态和操作时序加密保存在 private，Job 已删、资源归零。
- 确定两处实现缺口：点击一格后 `rc-imageselect-dynamic-selected` 使整体网格未就绪，旧逻辑随即放弃其他未变化格的答案；约 4 秒后动态 class 移除、`image.complete=true`，图块 opacity 仍为 **0.01**，旧逻辑提前把空白渐显图送给 AI，随后真实图片完全显示又使旧截图失效。不是依据验证码出现推测的分数或容器指纹结论。
- 补丁新增 Arena 内部 `GridCellSnapshot`，记录每格图片来源和就绪状态；继续点击前仅在题目、网格、目标格来源均匹配且目标格可用时沿用答案，其他格加载不使该答案失效。完整识别就绪增加实际透明度/可见性与自然尺寸检查，等待所有格可见后才再次识别。
- 五个回归先真实失败：剩余未变化答案被丢弃，以及 4 类渐显/不可见状态错误就绪。修复保留未知逐格信息时的整体保护、灰化/禁用、题目或目标格变化拒绝、3 次 AI/单次 30 秒/总 90 秒及取消截止；不改引擎、指纹、代理或 OpenAI/数据库。统一版本占用 1.6.6，未覆盖已有制品。
- 本地全量 **851 passed / 3 Redis 条件跳过 / 1 既有 Starlette 弃用警告**，Ruff/152 文件格式、Backend test 有效缓存/新 1.6.6 bootJar、Web lint/TypeScript/build 通过；版本契约、新不可变容器与限次真实验收继续。
