# Arena 页面上下文与原生提交修复（1.5.8）

时间均为 UTC+8。对应 [修复规格](../requirements/ARENA_PAGE_WORLD_RECOVERY_1.5.8.md)。

## 当前结论

- **1.5.8 已发布**：源码 `ec6ccf48ef90b8839af99759252a180ea1631b73`，CI 成功，四应用实际版本和镜像 digest 匹配，Argo Synced/Healthy。
- **已按用户要求退回 Self Proxy 四节点**：固定 HE IPv6 池未通过稳定验收，23:54 恢复 Arena 推理、生命周期、注册三个作用域至 `Self-hosted Oracle`；其他厂商绑定保持。候选池停用并保留配置，服务未删除。
- **Arena 有部分恢复证据，未全面恢复**：修复后的生产 Chat 已多次返回正文及 Completed；切回后唯一一次公网 Responses 仍被官方 v2/限流拦截，无有效正文。不能把健康检查、连通性或这些 Chat 成功扩大为所有会话/协议已通过。
- **额外运行风险**：共享 Automation 在 23:38:39 因 OOMKilled/137 重启一次，23:57 已 Ready；Arena、Server、Web 零重启。该问题与官方验证分开处理。

## 定位证据

| 对照 | 结果与边界 |
| --- | --- |
| 本机 Edge，HY2 Sophia IPv6 | Max “你好”完整回答；账号状态只在受限 private 文件和诊断内存中使用 |
| 服务端 Chromium headful，同账号/同 IPv6 | 原生提交 200，网页历史独立确认完整回答；服务端 Linux 有可用环境 |
| 1.5.5 Patchright 原桥接 | 隔离脚本世界看不到主页面 SDK，v3 unavailable，未发生成；Patchright 1.61.2 默认 `isolated_context=True` 与现场一致 |
| 仅修主世界 | SDK/cx 可读，一次生成仍为 403/v2；不能仅凭脚本可读判定恢复 |
| 主世界 + 本次原生 UI Token/cx | 隔离桥接 23 字正文、`ad` 正常结束、200、无流错误；一次真实生成 |
| 历史服务器账号，1.5.6 候选 | 首次完整回答，第二次 UI 准备超时且未发生成；候选不发布、不复用版本号 |
| 同历史账号，1.5.7 候选 | 首次 22 字正文 + `ad`；第二次正常发出但上游 429/验证升级，未确认连续两次成功；两次仅创建一个浏览器 |

这些对照支持修复浏览器执行环境与提交路径。并未证明所有 IP、账号或浏览器引擎都可用；429 与官方交互式验证仍需独立判定。

## Changelog

### 页面脚本：`official_browser.py`、`page_fetch_browser.py`

- `evaluate_in_page_world` 只对 Patchright Page 传 `isolated_context=False`，普通 Playwright/Camoufox 保持既有签名；不通过捕获 TypeError 重试脚本，避免请求副作用重复。
- 页面 fetch、Arena SDK/模块导出、上传及取消 controller 使用一致页面世界。
- `prepare_stream_request` 是账号会话所有权内的内部 hook，默认保留原 `before_stream_request` 行为，不改变其他厂商请求结构或外部接口。

### Arena 提交：`arena_browser.py`

- 以当前规范正文准备官方 UI 提交，拦截准备请求并本地结束，不向上游生成；只提取本次 `cx` 和 v3 Token，随后实际桥接请求使用原模型、完整正文和上传附件。
- 准备阶段 30 秒预算，缺少字段失败，取消/异常移除临时 route。Token 不写入凭据、日志或会话缓存；v2 重试只使用新 v2 Token，不夹带原 v3。
- 使用过的页面在下一次准备前回到新草稿，保留浏览器、Context、代理和已有资源生命周期；WeakSet 不持有已关闭页面。纯附件准备使用内部占位草稿，实际发出的原空正文及附件保持。
- 生成时长使用已有 `inference_timeout_seconds`（默认 240），目录/账号读取继续使用 canary 规则，防止生成被目录的 60 秒上限截断。

### 兼容性与测试

- 1.5.8 追加修复全局目录缓存混入账号 `credential_patch` / 计划 `runtime_reports`：缓存仅保留公共目录、状态、内容类型和传输模式；首次请求仍返回本账号的新状态，其他账号缓存命中或刷新失败回退均不接收该状态。
- Backend/Web/Automation 与锁文件统一 1.5.8。无 API、权限、数据库迁移；DeepSeek/Qwen 仍停用，不恢复周期生成探活或模型可用性判定。
- 新增脚本世界签名/副作用、原生准备缺字段/超时/取消、连续准备重置、附件与正文保留、原生 Token 不被覆盖、v2 兼容、内部 hook 失败不 fetch、推理 deadline 回归。
- 1.5.7 本地全量 655 passed（含真实 Redis），Ruff check/138 文件 format check、Backend `test`（缓存有效）/新 bootJar、Web ESLint/生产构建/TypeScript、源码与 JAR 版本契约通过。保留一项既有 Starlette/httpx 弃用警告；CI 和运行态另行回写。
- 1.5.6 本地门禁通过，但因连续请求现场缺陷未发布，版本占用不回收。
- 1.5.7 源码 `8e8e5e7` 的 CI [39139806326](https://github.com/Prodigalgal/any2api/actions/runs/39139806326) 三项质量门禁均成功；复查发现上述缓存隔离缺陷，主动取消镜像/GitOps 发布。1.5.7 不记为已部署；1.5.8 全量与发布结果另行回写。
- 1.5.8 本地 657 passed（含真实 Redis），Ruff/138 文件格式、Backend `test`（缓存有效）/新 bootJar、Web lint/build/TypeScript、源码/JAR 版本契约通过；CI 三项完整质量门禁与镜像/GitOps 发布均成功。

### 生产运营数据与代理

- 使用现有管理 API 导入更新已有账号，不新增账号、权限、接口或数据库结构。关闭额外生命周期调度，未恢复生成探活。
- 历史账号 `0c66aa4d-da59-41e4-9e6a-8c6d757009a9` 改用 Patchright，移除不适用的旧 Firefox 运行指纹配置，保留身份、账号元数据、保存状态、最大并发 1，将优先级提升至 100。其后生产 Chat 有正文和正常完成事件，因此保留该环境恢复。
- 第二账号 `030eb4e2-259e-4862-a0a9-27ead662af55` 的恢复尝试未通过公网生成，已通过备份回滚。23:57 确认恢复为 Camoufox、ACTIVE、优先级 0，不记为已恢复的高优先级账号。
- 退回 Self Proxy 时，以实时池绑定为基线，仅迁移 Arena 三作用域，逐项比较其他厂商绑定；将 HE 候选池设为 disabled。优先账号只调整 `proxy_affinity_key`，保留最新 Chromium 执行状态、优先级与元数据；使用生产安装代码的 VLESS 身份排序核对目标为 Sophia `168.138.40.214`。
- 切回后的官方拒绝已按现有健康规则将优先账号标为 DEGRADED；未人为清除失败记录或强制标记为可用。

## 发布与运行检查

- 源码：`ec6ccf48ef90b8839af99759252a180ea1631b73`。
- [CI 39140342859](https://github.com/Prodigalgal/any2api/actions/runs/39140342859)：success。
- GitOps/Argo revision：`2ea66b93117f3471e64639325df7ee92c9efe761`，23:57 仍为 Synced/Healthy。
- 不可变镜像公共后缀：`20261018-v1.5.8-release-ec6ccf48ef90b8839af99759252a180ea1631b73`。以下 digest 为 registry index/运行 imageID；对应 arm64 子清单已另行逐项核对一致。

| 应用 | Pod | 镜像 index digest | 23:57 状态 |
| --- | --- | --- | --- |
| Backend | `any2api-server-56c9ccc756-wg4tn` | `sha256:e8c3305512787ca33807709d8066af4fd52b03f5b48a5444e5dc47564d6d7fe4` | 1.5.8，Ready，0 重启 |
| 共享 Automation | `any2api-automation-866578f645-8z4vb` | `sha256:ceb95fffe6cccde28895e6bcf27710266ce18f91e2a53661dd5ece4ae406b536` | 1.5.8，Ready，1 次 OOM 重启 |
| Arena Automation | `any2api-automation-arena-6dbf8b9b7b-xgssx` | `sha256:7f68f941961c5d5594678c87c417c6f788e9f0121ef136816468f385e3d173e8` | 1.5.8，Ready，0 重启 |
| Web | `any2api-web-64dbdbdcfc-pfsrr` | `sha256:b0fa3730ef55be72df3f1855f4ad7196af1cb973480465d8ddca2d6bf75ea8d6` | 1.5.8，Ready，0 重启 |

- 两个公网入口的 `/healthz`、`/readyz` 均为 200；无密钥 Chat/Responses 返回 401、OpenAI 错误结构及 request_id，六家独立 Key 的目录读取均为 200。23:57 再次检查两个入口健康通过。本项没有发送厂商生成请求。
- DeepSeek/Qwen 数据库仍 disabled，对应部署零副本，未恢复注册调度或模型可用性判定。

## IPv6 池与生产验收

- 固定 IPv6 服务四节点的 `api64.ipify.org` 检查均 200，实际地址与各节点配置逐一一致，单次约 1.8–3.3 秒；只证明代理连通和出口固定。

### 固定 HE IPv6 候选池

| 节点出口 | 修复后 Chromium 桥接 | 耗时 |
| --- | --- | --- |
| `2001:470:36:3ea:b92e:999:be2e:82bf` | 同一历史账号，23 字正文 + `ad`，200，无 v2 | 28.162 秒 |
| `2001:470:36:3ea:7383:cd0e:f4cd:cd72` | 同一历史账号，23 字正文 + `ad`，200，无 v2 | 30.761 秒 |
| `2001:470:36:3ea:da5e:21c3:7b28:1b61` | 同一历史账号，429/v2，无正文 | 49.630 秒 |
| `2001:470:36:3ea:cbcd:d91f:fda8:87b5` | 同一历史账号，429/v2，无正文 | 52.306 秒 |

另一份历史成功账号在节点 3、再回节点 1，以及本机 Edge 状态迁移至节点 1 的最小对照也均遇 429/v2。不能将节点 3/4 简单判为坏节点，也不能把全部失败单独归因于 IP 或环境。候选池有真实生成能力，稳定性验收未通过。以上隔离诊断使用显式代理 URL，未覆盖生产动态池的租约及页面资源预检路径。

### 生产有效输出与公网协议

- 23:35:17 开始的生产 Chat 请求 `28b845c2-abde-4db5-b8b6-4617bf80f82f`，账号 `0c66…`、HE 节点 1，返回 166 字正文、Usage、Completed(stop)，总时长 107.807 秒，首内部事件 56.142 秒。
- 23:45～23:55:39 的已结束 Arena 请求有 4 条，均使用该账号并取得正文及 Completed(stop)，对应 request_id：`51cfbf81-8ef0-46c4-8483-06b4c0381152`、`2d232708-8d42-469d-8d3a-19aabb9a9d66`、`68e65c46-536f-4871-8a6e-a41765e69807`、`b7c22222-007f-41c6-b196-25d679f4c371`。这些请求开始于切回之前，不能计为 Self Proxy 切换后的生成验收；最后一条跨越切换时刻，原请求保留原租约正常完成。
- 本轮公网 Responses 均为普通 `arena/Max`、“你好”、stream 请求，没有携带账户选择参数，也未修改路由以强制测试。

| 开始时间 | request_id | 结果 |
| --- | --- | --- |
| 23:36:13，HE 池 | `77850bbd-2912-45cd-bf7c-5b71ee5592d5` | 无正文，94.401 秒，首帧准备预算触发 504；当时优先账号在途，实际选到另一账号 |
| 23:44:49，HE 池 | `cb0e97e9-be69-46ef-97ab-cf451c48fec3` | 无正文，53.053 秒，429/v2；第二账号恢复随后回滚 |
| 23:56:00，Self Proxy | `d9ec8319-1419-4eb2-a3bc-de9f9444d5e8` | 无正文，49.955 秒，429/v2；数据库确认使用 `0c66…`，标准 `anti_bot_rejected`，上游 `prompt_rate_limit` |

SSE 响应一旦发送 created/in_progress，HTTP 状态已为 200；随后错误是否符合 OpenAI 结构与是否取得完整有效回答是两个检查项。本轮错误契约有记录，有效输出验收未通过。

### 退回 Self Proxy 的验证

23:54:16 开始切换，23:54:38 完成账号 affinity 更新。管理 API 与数据库双重核对：Arena 三作用域均指向池 `496c8e5a-e983-45df-a7f7-084294ac2226`，4 节点；HE 池无厂商绑定且 disabled，其他厂商的池与作用域逐项保持。

使用当前 Arena Pod 的 `proxy_lease`、Redis reservation、sing-box 路径检查，不发送模型生成请求：

| 节点入口 | 实测出口 | gstatic | 合计耗时 |
| --- | --- | --- | --- |
| `168.138.40.52` | `2603:c023:c:e17e:c378:b690:56ab:dfd8` | 204 | 1.450 秒 |
| `168.138.40.214` | `2603:c023:c:e17e:7451:c7af:1783:e35` | 204 | 1.130 秒 |
| `213.35.105.106` | `2603:c024:4518:57e:0:92ed:9ebe:86bd` | 204 | 2.231 秒 |
| `217.142.229.2` | `2603:c023:c:e17e:25b4:1f49:6d5b:f220` | 204 | 1.231 秒 |

Sophia 节点的原始 Arena 页面请求为 403，当前预检接受该状态；这只能证明路由可达，不能证明验证码通过。四个原 VLESS 节点当前均从 Oracle 原生 IPv6 出站；此次退回的是原 Self Proxy 池，没有将全部流量强制改成 IPv4。

## 性能与可靠性遗留

- 账号保活和推理共用 2 个浏览器会话槽，曾观察到 30～51 秒排队及频繁逐出。健康端点毫秒级不能代替首帧/正文时延验收，盲目增加并发还会增加内存压力。
- 23:38:39，共享 Automation 上一次容器退出为 `OOMKilled` / 137；当前 memory limit 为 3Gi。23:57 Pod Ready，随后内存采样约 1919Mi，Arena 约 1159Mi。还需围绕浏览器峰值内存和后台保活调度处理，未在本轮直接扩容或改并发。
- 23:56 资源样本：共享 Automation 健康耗时 3.99～13.86 ms，VLESS 创建 4/复用 103；Arena 健康 3.10～9.06 ms，VLESS 创建 2/复用 1。代理采用现有持久隧道池，未退回每请求销毁模式。
- Arena 进程清理器累计有 2 次 stalled 回收，受管根计数为 2；该计数本身不足以证明误杀或失控，未直接改变清理策略。
- 部分旧凭据的 legacy cookie 与新保存 storage state 有差异；权威来源及跨引擎迁移仍需回归证据，尚未证明它是本次 v2 的根因，未盲目批量改写所有账号。
- 23:54:39～23:57:22 的已结束流量中，MiMo 4 次、MiniMax 2 次有有效输出；Grok 1 请求经两次反爬失败后第三次完成。不能据此宣称其他厂商全时段无错误；GLM/LongCat 本窗口未新增生成验收。

## 风险与回滚

- 官方 UI/页面模块会变化，准备失败须明确结束，不能静默复用旧 Token 或假定验证已通过。
- 目前尚未取得全部账号、Responses、工具/多模态的现场恢复验收；正文/附件保留有回归测试，不能替代多模态实测。官方 v2、限流与系统资源等待分开归因，不复用旧 Token、不伪造验证成功。
- 应用回滚使用源码 `0a2a0a97360597a8a98c411b36fd7559c06147f9` 对应 1.5.5 四应用不可变镜像，保留现在的 Self Proxy 绑定，不能复用或覆盖 1.5.8 制品。1.5.6/1.5.7 均未部署，版本号不回收。
- 运营备份保存在 `D:\WorkSpace\Project\服务器管理\private\any2api`，文件仅当前用户/SYSTEM/Administrators 可读，账号凭据备份仍为原 AES-GCM 密文，未包含主密钥：
  - `arena-runtime-before-1.5.8-0c66aa4d-da59-41e4-9e6a-8c6d757009a9-20261018T153513Z.json`：首次环境恢复前，可经现有账号导入契约恢复原身份/元数据/执行状态。
  - `arena-runtime-before-1.5.8-030eb4e2-259e-4862-a0a9-27ead662af55-20261018T154435Z.json`：第二账号恢复前，已据此回滚。
  - `arena-self-proxy-binding-before-1.5.8-20261018T155416Z.json`：退回前的实时池视图。若未来需恢复候选池，应再次读取当前绑定，仅恢复 Arena，不能将旧快照整池覆盖其他厂商。
  - `arena-affinity-before-self-proxy-1.5.8-0c66aa4d-da59-41e4-9e6a-8c6d757009a9-20261018T155436Z.json`：仅改 affinity 前的账号密文快照。回滚也应以实时账号为基线，优先恢复该字段，避免用旧快照覆盖后续自然请求保存的状态。
- 验证元数据位于本地忽略目录 `backend/build`：`production-v158-verified-20261018.json`、`production-v158-public-20261018.json`、`production-v158-registry-digests-20261018.json`、`arena-self-proxy-restore-v158.json`、`arena-restored-self-proxy-check-v158.json`、`arena-self-proxy-final-state-v158.json`、三次公网 Responses 元数据；不包含明文 Key/Cookie/正文。
- 本次最终回写只改需求边界、运行证据、代理语义和任务板，不产生新可部署源码候选；生产保持 1.5.8。
