# Arena 浏览器环境与原生提交排查

时间：2026-10-19，UTC+8；生产基线：1.5.13。对应[排查规格](../requirements/ARENA_ENVIRONMENT_INVESTIGATION_2026-10-19.md)。

## 当前结论

- **Arena 尚未恢复。** 对新导入会话执行同账号、同 Sophia 出口的隔离诊断；正常网页网络路径下，服务器有窗口 Chromium 的原生 UI 提交仍返回 `403 / recaptcha validation failed`，没有正文或完成事件。
- 本次失败能够在不经过 Any2API 规范正文重建、Token/cx 提取及桥接 fetch 的路径上复现。该结果把当前阻断缩小到服务器会话的上游验证环节，不能据此断言全部桥接功能已经正确，也不能确定 Google/Arena 的内部拒绝原因。
- 登录态有效、官方模型目录可读、官方页面能够产生新 Token/cx，均不代表生成验证已被接受。单纯切换有窗口模式、取消旧 Headless UA 恢复，未解决本次故障。
- 没有找到足以恢复生成的代码修复依据，**无应用代码或生产配置变更**，没有发布新版本。生产继续运行 1.5.13。

## 运行环境与控制变量

- 隔离容器使用当前生产 Arena 的不可变镜像：`automation-arena-20261019-v1.5.13-release-bae94d4f6139bb54d3691a6f0a4768b659b731c2`，运行 digest 为 `sha256:1acafa5ad3c377a389981f8ede39b14bd8eade38602936ee82cff7204268f652`。
- 账号为此前独立导入的 `0bb13730-997e-4488-83e3-8df724d29752`。凭据从当前加密记录解密至内存，仅通过 stdin 传入诊断进程；未保存 Cookie、Token、邮件地址或回答正文。
- 使用生产代码解析原 Self Proxy 四节点并排序，固定 affinity 指向 Sophia `168.138.40.214`，不轮换出口。隔离容器只使用本地 reservation，生产账号租约和代理绑定不修改。
- 临时 Job 无 Service、无端口暴露、无 ServiceAccount Token，使用独立 `any2api-arena-diagnostic` 标签，不能匹配生产 Automation 的 Service selector。浏览器预算 1，CPU limit 1、memory request 1Gi/limit 3Gi，运行于较空闲节点。
- 生产主进程确实持有 `DISPLAY=:99`；`kubectl exec` 子进程缺少该动态环境变量，不能用于判断主进程没有显示服务。当前 Runtime 仍默认读取 `registration_headless=true`。

## 零生成对照

所有准备阶段的生成 POST 均本地拦截。以下三组均加载 250 个模型，原生与规范正文均为相同 7 个顶层字段，`userMessage` 为相同 3 个字段；mode、modality、模型和内容一致。字段数按本次纯文本页面请求计，不沿用历史报告中的计数。

| 开始时间 | 启动/保存状态 | 官方 SDK | cx 长度 | 新 v3 Token 长度 | 总耗时 | 真实生成 |
| --- | --- | --- | --- | --- | --- | --- |
| 12:05:15 | 无窗口、恢复现存指纹 | 可用 | 20 | 2617 | 17.315s | 0 |
| 同一进程随后 | 有窗口、恢复现存指纹 | 可用 | 20 | 2553 | 21.404s | 0 |
| 12:08:46 | 有窗口、使用浏览器默认环境，保留原认证状态 | 可用 | 20 | 2532 | 17.802s | 0 |

发现现存 `runtime_fingerprint.user_agent` 包含 `HeadlessChrome`；`context_options` 恢复该值，使第二组即使有窗口运行也继续报告 Headless UA。因此第二组不能视为去除旧 UA 干扰的对照。第三组仅在诊断内存副本中移除 `runtime_fingerprint`，浏览器实际无 `--headless`，页面也不再报告 Headless UA；没有更改生产凭据。

第一、二组只阻断生成端点；第三组曾将 Arena 后台 POST 一并阻断，故该组只用于字段准备和环境检查，不用作正常网页生成结论。

## 认证身份核实

- 保存状态中的认证 JWT subject 与库存 `arena_user_id` 一致，`is_anonymous=false`；当前存储 Token 在检查时未过期，浏览器恢复后也保持相同身份。
- `/api/me` 的 `user.id` 是资料 ID，不是导入时使用的 Supabase subject。最初仅比较 `id` 所得的 `identity_matches=false` 是诊断字段选择不完整，**没有证实串号或游客回退**。
- 最终对照使用 `user.supabaseUserId` 比较，且邮箱只在内存比较：两者均匹配。`/api/me` 返回 200，当前浏览器 JWT 非匿名、未过期。
- 原始加密导入备份中的旧 access token 已过期，当前服务器保存记录已有更新后的有效 Token；不能直接把旧备份当作当前仍有效的完整会话。

## 两次原生生成及其边界

两次均为当前账号、Sophia、有窗口 Chromium、浏览器默认环境、Max、同一条合成短输入。每个容器最多允许一次生成 POST，不自动重放、不点击验证码、不调用桥接的 v2 checkbox helper。

| 开始时间 | 网络条件 | 实际生成 POST | 上游结果 | 生成耗时 | 有效输出 |
| --- | --- | --- | --- | --- | --- |
| 12:10:39 | 诊断曾拦截 7 条其他后台 POST | 1 | 403，39 bytes，`recaptcha validation failed` | 2.610s | 否 |
| 12:12:04 | 正常后台请求全部放行，仅阻止额外生成；实际拦截数 0 | 1 | 403，39 bytes，同一错误 | 2.315s | 否 |

第一条保留为受诊断拦截影响的初步结果。为消除这项具体干扰，第二条恢复正常网页网络行为；官方原生提交仍被拒绝。因此不能将第一次失败简单归因于诊断阻断后台请求。

本轮真实生成合计 **2 次**，全部失败；没有继续尝试桥接生成或轮换账号/代理来重复撞验证。隔离诊断不经过 Java INFERENCE 账本，不能计入生产业务成功样本。

官方返回的原文是 `recaptcha validation failed`；`recaptcha_v2_required` 是现有桥接流程的兼容错误标签，不能当作官方已公开的内部判定原因。当前没有 Arena 后端的 assessment score/reason 证据。

Google 文档说明 Token 单次使用且两分钟过期；不能把本机过去通过验证的 Token 迁移后反复使用。本次仅由官方页面产生本次请求的新 Token。[Google 官方说明](https://docs.cloud.google.com/recaptcha/docs/create-assessment-website)

## 清理、运行复核与兼容性

- 四个临时 Job（尾号 `040454`、`040833`、`041027`、`041152`）全部删除；最终 Kubernetes 查询无诊断 Job/Pod。
- 每组关闭后浏览器预算 `in_use=0`，代理池关闭后 `total=0/references=0`；诊断进程未留下浏览器。没有在生产 Pod 另起不受预算约束的浏览器。
- 新账号仍为 `PENDING / enabled=false`；唯一初次检查仍为 `FAILED / InitialVerificationInterrupted`，完成时间仍为 `02:22:42.861868Z`。没有重置、删除、重试该预算，也未人为写入成功。
- 12:13:41 再次核对四组件实际版本和运行 digest，均为 1.5.13、Ready、0 重启；Argo 为 `Synced/Healthy`，GitOps revision `af27df0a1f95a98938e58159a3c071ed4186e13d`。DeepSeek/Qwen 均为 0 副本。
- 不新增 API、权限、数据库迁移或配置，不改变原四节点绑定、不恢复周期生成探活。没有产生新应用候选，未占用或复用新版本号。

## 下一步边界

1. 工具目前报告 Work 设置禁用本地浏览器使用。已请求恢复访问，等待后续核对仍能聊天的 Edge 原会话；未读取本机浏览器数据库、Google 登录态或采用其他控制通道绕过该设置。
2. 本轮创建的本机空白 Arena 诊断标签页 `491005372` 尚需在恢复浏览器控制后关闭；用户原会话页 `491005319` 保持。没有在本机提交新生成。
3. 恢复访问后，先做 Arena 同源的当前状态与环境只读比较，避免将 Cookie 备份等同于完整验证上下文。完整存储状态跨设备恢复能否改变结果尚未验证，不能承诺有效。
4. 若官方仍要求在服务器实际会话完成交互验证，需明确恢复方式。现有授权不包含新增人工验证管理 API/权限；本次不扩展该边界，也不通过改 UA、反复换出口或自动解题继续尝试。

## Changelog 与回滚点

| 分类 | 文件/模块 | 行为与验证 | 兼容性/回滚 |
| --- | --- | --- | --- |
| 排查规格 | `docs/requirements/ARENA_ENVIRONMENT_INVESTIGATION_2026-10-19.md` | 固定账号/出口、隔离所有权、两次生成预算与恢复验收边界 | 文档变更，无运行影响 |
| 调查记录 | 本报告、`tasks/current.md` | 记录准备通过、认证字段区别、正常原生生成 403、当前发布状态和未完成事项 | 文档提交可单独回退，无应用/数据迁移 |
| 本地诊断脚本 | 忽略目录 `backend/build/arena_environment_diagnostic_20261019.py`、`arena_session_metadata_20261019.py` | 加密凭据内存传输、固定原代理、生成限次、元数据输出及 finally 清理；实际完成 3 组准备和 2 次原生生成 | 未进入应用镜像；临时 Job 已删除，无需回滚生产 |
| 生产复核 | `verify_release_v1513.py runtime`、只读账号/初检查询、Pod/Argo 查询 | 四组件版本/digest/健康与账号初检预算保持 | 生产继续使用 1.5.13，不发布未经证明的修复 |

本轮未改应用源码，未重复运行无关 Backend/Web/Automation 全量测试；诊断脚本通过 Python 编译检查，证据文件完成生成次数、清理和版本一致性断言。遗留风险是 Arena 上游验证仍拒绝当前服务器会话，本机当前状态尚未重新读取。
