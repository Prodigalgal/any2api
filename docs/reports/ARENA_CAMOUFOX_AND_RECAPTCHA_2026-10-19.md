# Arena Camoufox、多个账号与 reCAPTCHA 排查

## 当前结论

2026-10-19，生产仍为 1.5.16，Arena 真实推理尚未恢复。1.5.17 已构建，修复模板输入准备；1.5.18 统一 Arena 的 Camoufox 引擎与账号环境，CI 通过后在隔离验收发现冷启动扩展不一致；后续修复占用 1.5.19，不覆盖此前制品。本文只记录有证据的状态；登录成功、指纹生效、HTTP 成功及厂家真实输出分别验收。

用户最新要求 Arena 使用 Camoufox、不设置引擎回退；同账号不因启动或重认证失败而切换到 Patchright。其余五家、原四节点代理绑定、一次入库检查和 DeepSeek/Qwen 停用状态保持。

## 新情况：官方图块验证及诊断状态误判

### 两种网页状态

- **Code 模板必填校验**：用户先前截图为 `/code/direct` 的电商模板，未填完字段时发送按钮不可用。1.5.17 已修复退出模板、选择可见消息输入框、核对原文及取消清理，详见[专项报告](ARENA_PROMPT_TEMPLATE_1.5.17_2026-10-19.md)。
- **官方 reCAPTCHA 图块**：临时服务器浏览器发出一条“你好”后，生成返回 403，网页呈现 Security Verification 和 4×4 图片题。此前自动流程仅尝试 checkbox，没有接入图块视觉 AI 的完整状态闭环；`/internal/v1/captcha/solve` 当前只公开既有 ocr/slider/dots/tap 类型。

模板校验发生在发送前；本次图块发生在已发出生成请求之后。两者需按 DOM、生成 POST、官方响应分别记录。

### 已执行的 6 次内部 AI 调用

用户分两次分别确认最多 3 次调用，均已用完。只向内部 AI 发送验证码区域，不包含侧栏、账号信息或聊天历史；模型显式选取 MiMo、LongCat、GLM，避开调用回 Arena。

| 调用 | 模型 | 实际结果 | 操作与判定 |
| --- | --- | --- | --- |
| 1 | MiMo `mimo-v2.6-pro` | 返回含多余尾部的非法 JSON | 严格解析拒绝，未点击或提交；内部请求 success 不代表验证码通过 |
| 2 | LongCat `longcat-pro` | 45 秒读取超时 | 没有机器结构，未点击或提交；不能记为厂家返回非法 JSON |
| 3 | GLM `GLM-5v-Turbo` | 合法 JSON，但把已勾选格编号读错 | 初始校验拒绝；随后用既有识别结果和实际画面取消原勾选并点击 Next，0 次追加 AI；未通过 |
| 4～6 | GLM `GLM-5v-Turbo` | 各返回合法空选择集 | 诊断曾重复识别提交等待中的灰化图片；不能记为三道新题或三次官方有效提交 |

第 1 次输出原文为 `{"select":[],"selected":[7]}]}`。本次只确认返回内容不符合 JSON，未取得逐层原始流证据，尚不能定位为模型、上游协议或解码器的某一层错误；不以截断尾部伪造成功。

图像复核显示：第 1～3 次裁剪 RGB 哈希相同，第 4～6 次裁剪 RGB 哈希相同。最终画面仍是同一题，图层灰化、SKIP 按钮禁用。此前“Google 已换下一题”的判断错误，属于临时诊断流程遗漏了提交中状态；已向用户更正。

- 临时脚本已增加提交按钮有效性检查，提交中/禁用时停止识别和点击。实际录屏回放验证：初始可提交、最终不可提交；不可提交状态 AI 调用为 0。
- 该保护基于这次诊断的截图，不是产品通用验证码实现。正式闭环需要 iframe DOM、网格选择与按钮状态、验证码网络事件和统一总 deadline；不得凭截图变化或一次点击返回就认定换题/通过。
- 本次没有采集到官方验证码校验请求的完整网络元数据，实际有效提交次数未知，也不能确定等待卡住的根因。
- 当前没有通过验证、没有新有效正文，也没有导出成功会话；不继续自动识别或提交。新的验证码操作需要当次单独确认，来源为浏览器操作规则。

### 临时私有入口已关闭

Job `any2api-arena-operator-20261019093217` 于约 17:32 启动，约 18:02 到期后关闭。最终实际 Arena 生成 1 次、有效输出 0；独立 Job 已删除，本机 port-forward 关闭、私有管理文件标记 inactive，浏览器预算及代理引用归零。它没有进入产品镜像，也没有新增生产管理 API。

页面顶部的 `HTTPNotFound` 来自临时入口把 favicon 404 记作操作错误，已在忽略目录的脚本修正并编译检查；未重启原会话做运行验收，不能把它解释为 Arena 403。

## 多账号对照

按用户要求，不只使用一个账号。先前三个新增账号分别在有窗口 Chromium 中各发送一次，认证身份均匹配、生成均为官方 403；该轮是显式引擎对照，没有回写生产账号 backend。

用户要求改为 Camoufox 后，18:46～18:50 使用同三个账号的**原始 Camoufox 配置**与各自原保存的 Self Proxy affinity，各放行最多一条原生“你好”，不自动操作 CAPTCHA：

| 账号前缀 | 原代理节点 | 保存/实际时区 | 指纹与身份 | 生成结果 |
| --- | --- | --- | --- | --- |
| `030eb4e2` | `168.138.40.52` | `Asia/Singapore` | 49 个配置字段完整恢复，身份匹配 | 1 POST，403 `recaptcha validation failed` |
| `28682cbe` | `168.138.40.214` | `Asia/Tokyo` | 49 个配置字段完整恢复，身份匹配 | 1 POST，同上 |
| `20b74a99` | `168.138.40.52` | `Asia/Tokyo` | 49 个配置字段完整恢复，身份匹配 | 1 POST，同上 |

三个浏览器均为 Camoufox `152.0.4-beta.28`、有窗口、报告 Win32/Firefox，`navigator.webdriver=false`、页面 visible、WebGL 实际可创建。字体列表、Canvas/字体种子、WebGL、地理位置及 WebRTC 代理地址配置存在，`humanize=true`；二次借用返回同一浏览器对象，没有重新随机指纹。

本轮使用 1.5.17 不可变镜像，digest 为 `sha256:18c4be7b2966eb843ea6e086afb79e06b392a3d3a44e1250721917e5b40caa40`。每条均认证 200、预期 profile 匹配、本次输入/模型字段匹配，生成响应 39 bytes、无正文/完成。三 Job 全部删除，浏览器预算、代理引用归零；没有修改生产账号或初检状态。

这些证据确认 Camoufox 指纹配置已生效，尚不能证明 Google 分数提高或判定所有账号、四个出口均同因故障。本轮只覆盖两个原代理节点，未查看官方 assessment score；浏览器报告的硬件标识不代表容器实际为 Windows/NVIDIA。

## 1.5.18～1.5.19 变更与兼容性

| 分类 | 文件/模块 | 行为变化 | 兼容性与回滚点 |
| --- | --- | --- | --- |
| 引擎策略 | `providers/official_browser.py`、`providers/arena_browser.py` | Arena 显式固定 Camoufox 和有窗口推理；保存的 Patchright backend 不再覆盖。启动失败走明确错误和资源回收 | 共享 Runtime 默认策略保持，其他厂商仍使用原规则；回滚原不可变镜像 |
| 生命周期 | `providers/arena.py` | 注册/重认证取消 Patchright fallback；重认证恢复保存的 Camoufox 配置和 Arena 存储，优先原账号 affinity、偏移归零、严格绑定 | 不新增字段/API/数据库迁移，不轮换四节点；旧 Patchright 账号首次迁移到 Camoufox 后按原 credential patch 保存 |
| 配置一致性 | `providers/arena.py`、`providers/official_browser.py` | Camoufox 接管 locale/时区/viewport；恢复配置时启用原生 humanize，保留设备种子；避免外层固定 en-US/UTC/尺寸覆盖。损坏的保存配置明确报错，不静默生成新身份 | 不按请求再生成已有配置；无配置账号沿原 Camoufox 原生生成和 GeoIP 路径 |
| 交互一致性 | `providers/arena_browser.py`、脚本行为回归 | 保留浏览器原生鼠标与滚轮，移除重复 DOM 合成鼠标预热；现有原生上下文、Token 获取和协议流程保留 | 合成事件的 `isTrusted=false`，未证明它是 403 根因，不承诺删除后提高分数 |
| 冷启动/恢复一致性（1.5.19） | `lifecycle/browser.py`、`providers/official_browser.py`、Arena 两入口 | Arena 显式排除 Camoufox 默认扩展，使新配置与保存配置恢复时行为一致；保留字体/Canvas/WebGL/GeoIP 与 humanize | 其他厂商默认扩展保持；不改变账号、代理、数据库或 OpenAI 契约 |
| 回归 | `tests/test_arena_browser_policy.py` | 覆盖保存引擎覆盖、禁止失败回退、复用/持久化、启动失败资源回收、恢复种子及重认证环境不变性 | 使用隔离夹具，不新增厂家生成探活 |
| 统一版本 | Backend/Web/Automation 与锁文件 | 先后占用 `1.5.18`、`1.5.19`，保留已构建的所有候选 | 不覆盖既有镜像/版本；无数据库迁移 |
| 诊断与文档 | 本报告、恢复规格、模板报告和当前任务板 | 记录图块验证、AI 预算、误判更正、多账号结果和临时资源关闭 | 诊断脚本/原始会话保留于忽略或私有目录，不随镜像发布 |

Camoufox 官方说明支持原生指纹、GeoIP 和鼠标轨迹配置，并建议避免随意固定窗口或覆盖单项指纹。本项目沿用锁定的 `camoufox[geoip]==0.5.4` 和实际安装包支持项；配置生效并不保证验证通过。[官方参数说明](https://camoufox.com/python/usage/)

通过 `dispatchEvent()` 分发的事件，其 `isTrusted` 为 false；浏览器原生输入与这些合成事件应分别记录。[MDN Event.isTrusted](https://developer.mozilla.org/en-US/docs/Web/API/Event/isTrusted)

## 1.5.18 隔离验收发现及修正

- CI [39241168110](https://github.com/Prodigalgal/any2api/actions/runs/39241168110) 全部成功，Automation **742 passed**，镜像已发布到注册表，`deploy=false`，未更新 GitOps。源码 `49ba48cc4338b335452855bf8f9f3beea64b1a7a`；Arena digest `sha256:722f377095914fa2e5fc4f0358dfd5344189deb66ad109623fc76660265e5d6e`。
- 新增开启 humanize 的完整 Windows 模板夹具后，部分轮次在按钮可见/可用/稳定、已进入 performing click 的阶段超时，0 次厂家请求。首次夹具 CPU 为 600m，改为生产同级 1500m 后曾有一轮 12/12 桥接用例通过，但另一轮仍能复现点击超时，不能单独归因为 CPU。
- 简化空白页的 3 组点击对照均成功。使用同一完整页面、同一指纹/窗口、同一资源的进一步对照，冷启动带默认扩展的两个按钮各超时 6 秒；仅从初始配置移除扩展字段，Exit template 约 2.3 秒、Send message 约 1 秒成功，humanize 始终开启。该证据支持修正扩展生命周期，但未证明它导致官方 403。
- `camoufox_config_from_options()` 为避免保存不可移植的扩展路径，会移除 `addons`。因此冷启动/注册与保存配置恢复之间原本存在扩展差异；1.5.19 在 Arena 的两种启动路径都显式排除默认扩展，其他厂商保持原行为。
- 一轮夹具的关闭恢复校验还错误地要求保存 `http://127.0.0.1` 的 localStorage；产品按既有契约只保存 HTTPS origin，此断言不适用。夹具改为验证受允许的本地 Cookie，生产 HTTPS 存储过滤不变。
- 所有诊断 Job 都独立删除，预算/代理归零；失败记录保留，没有发出新的厂家生成，也没有将测试断言错误写成产品认证故障。

## 验证与发布状态

- 新引擎/恢复策略与相关所有权、上下文回归通过；最终完整 Automation 742 项中 **739 通过、3 个 Redis 集成条件跳过**，包括移除合成事件后的 JS 行为回归。Ruff 与 146 文件格式检查通过。
- Backend `test` 使用有效缓存，`bootJar` 重新构建成功；Web lint、TypeScript 与生产构建通过；1.5.18 源码及 JAR 版本契约通过。
- 1.5.19 本地 Automation **740 passed / 3 Redis 条件跳过**；Ruff/146 文件格式、Backend test 有效缓存与新 bootJar、Web lint/TypeScript/build、源码/JAR 版本契约全部通过。CI 与不可变候选容器结果待回写。
- 1.5.18 CI 成功，隔离验收问题转入 1.5.19。**生产未提升**。当前 1.5.17 的三个账号实测不能当作后续镜像验收。
- 仍需解决官方验证拒绝并完成真实正文/完成事件、Chat/Responses 与连续复用验收。生产保持原稳定镜像组合；不得用模拟通过替代恢复结论。
