# Arena 系统完整流程与 1.6.0～1.6.1 候选

## 当前结论

用户 2026-10-19 21:07 授权“自行注册、自行识别、自行测试”，随后明确要求系统自身完整流程。当前生产仍为 1.5.16，Arena 尚未取得新的真实正文；1.6.0 已完成 CI/容器，真实验收暴露的问题继续用新候选 1.6.1 修复，未提升生产。

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

## 遗留与回滚

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
