# 1.5.9 全系统可靠性审计与 1.5.10 验收修正

## 范围与结论边界

- 日期与时区：2026-10-19，UTC+8。基线为生产 1.5.8，源码 `9e6c394`；首个候选 1.5.9，最终部署 1.5.10。
- 覆盖生产 Pod/资源、PostgreSQL/Redis、账号租约与生命周期、浏览器/代理生命周期、流式取消、Arena 状态恢复、启用厂商适配、OpenAI 契约与管理端读取。
- DeepSeek/Qwen 继续停用且保持 0 副本；Arena 保持原 Self Proxy 四节点。独立 HE IPv6 池保持停用、无厂商绑定。
- 不新增生成探活、验证码 SaaS 或管理权限。本轮优先引用现有业务流量与零生成诊断；上线后的少量生成单独登记，不把健康检查或目录读取等同于有效回答。
- 本报告记录审计时确证的缺陷及取样边界，不宣称全部厂商、全部客户端或长期稳定性已全面通过。

## 缺陷与变更清单

| 级别 | 缺陷与证据 | 修复、关键模块与兼容性 |
| --- | --- | --- |
| P1 | 共享 Automation 曾 OOMKilled/137；审计时 3 个 Camoufox，cgroup 当前约 2.52Gi、重启后峰值约 2.85Gi，限额 3Gi。 | `automation-runtime-resources.yaml` 将共享实例 `ANY2API_AUTOMATION_BROWSER_PROCESS_CAPACITY` 设为 2；沿用统一预算、空闲会话驱逐及现有资源限额。1.5.9 首次配置漏前缀，线上仍为 3，该项未通过；1.5.10 修正并补部署解析回归。会增加高峰排队，需观察。 |
| P1 | 生命周期一次 claim 8 项、实际并发 2；未开始执行的任务没有续租，可能先耗尽 5 分钟租约；多厂商有 30 分钟以上逾期。 | `LifecycleScheduler` 只领取可执行并发数，同优先级按厂商轮转取第一项，再取后续项；保留优先级、`SKIP LOCKED`、执行中续租与幂等。真实 PostgreSQL 测试验证 claim 数量与公平性。 |
| P1 | keepalive 等待批处理通道与浏览器准备缺少操作总预算，长请求阻塞其他维护。 | Python `provider_api` 将 keepalive 通道等待和执行纳入已有首帧预算；Java `LifecycleAutomationClient` 增加 3 分钟 keepalive 总超时。注册/重授权保持原有策略。取消后的必要资源回收仍可能占用尾部时间。 |
| P1 | 近期成功推理后仍重复维护账号，争用浏览器。 | 仅对 ACTIVE/enabled、已结束初次验证、近期有普通成功 `INFERENCE`、非每日签到、非临近过期账号延后 keepalive；不使用 PROBE，不新增生成，不修改健康计数；原 owner/lease 条件和 action generation 保留。 |
| P1 | Java 出口门禁用 `hashCode` 取模，Python 实际使用 SHA256 rendezvous；429 作为普通 stream status 时未触发门禁冷却。 | `ProviderEgressGate` 对齐节点 identity、排序、offset 和 VLESS path 解码；无 affinity 时使用保守池级门禁；补齐 stream 429、原子间隔预留、共享领取预算及取消后的幂等释放。协议与代理绑定不变。 |
| P1 | 预租账号在 prompt cache 命中、目录失败和目录取消时未交给内层执行，原租约未释放。三条回归均在旧逻辑失败。 | `InferenceCoordinator` 用外层 `usingWhen` 保管预租账号，实际进入执行时才转移所有权；提前退出释放，正常重试仍走已有释放路径。缓存与业务协议不变。 |
| P1 | MiniMax 独立浏览器运行时没有继承通用关闭超时，且同步进程终止可能阻塞事件循环。 | `official_browser.close_owned_browser` 收敛原通用清理；MiniMax 正常关闭与启动失败复用同一清理、watchdog 与线程回收。保持回收完成后再归还预算/代理引用。 |
| P1 | MiMo 取消只取消 Python evaluate；通用 PageFetch 的取消 hook 是空实现，LongCat 继承该路径。 | 页面按 requestId 保存 AbortController，下游取消显式中止上游后释放会话；MiMo 使用推理超时而非注册超时，并补 Python evaluate 总边界。已有取消回归和新回归通过。 |
| P1 | Arena 多模态诊断把提示词片段和附件签名地址传到 Java INFO 日志。 | Python 改为阶段、附件数量和请求字节数；Java 只记录固定白名单阶段与数值，滚动升级期间也不记录旧 `data` 正文。真实请求内容和附件传输不改变。 |
| P2 | PageFetch/MiMo/Grok 的 active binding queue 无界；Arena/PageFetch/MiMo 未分帧文本可持续累积。 | 新 `BrowserStreamBuffer` 按既有字节配置与事件数限界，超限明确失败，保留已接收输出并允许结束控制帧；页面未分帧文本增加字节边界和清理。合成 Node 行为测试覆盖 Unicode 超限和 AbortController 回收。 |
| P2 | Arena 恢复 storage state 后，无条件再注入旧扁平 Cookie，覆盖有效值并丢失作用域。 | 仅 Arena 优先保留实际适用于 base URL 的恢复 Cookie，旧字段仅补缺失项；其他厂商优先级不变。合成测试及生产账号隔离诊断均验证。 |
| P2 | `prompt_rate_limit` 为桥接根据 429/`prompt failed` 推断的标签；v2 等待失败后原始错误丢失。 | 保留 `upstreamStatus/upstreamError/retryAfter`，标明 `triggerReasonSource=bridge_inferred`；已有 native Token/cx 后不再追加合成交互。保留既有错误 code，不把推断标签当成官方真实根因。 |
| P2 | Automation readiness 的 body 为 `ok=false` 时仍 HTTP 200。 | `main.ready` 使用 HTTP 503 表示未就绪，成功仍 200，响应 body 保留；liveness 不依赖外部厂商生成。 |
| P2 | 管理 GET 无总超时；高频筛选列表不取消过期请求；Playground 卸载不终止生成，旧请求 finally 可能覆盖新请求状态。 | `web/src/lib/api.ts` 管理 GET 默认 30 秒；账号/请求/操作列表传递 Query signal；Playground 卸载 abort，finally 校验当前控制器所有权。无页面视觉或后端 API 变更，写请求保持各自策略。 |

## Arena 零生成对照

使用临时隔离 Pod、当前生产账号的最新状态、候选源码、原 Self Proxy 的 Sophia `168.138.40.214`。凭据仅经 stdin/内存传递，临时 Pod 已请求删除；没有修改生产账号状态或触发真实生成。

- 恢复状态包含 21 条适用 Cookie，折算为 20 个唯一作用域；与 13 个旧扁平 Cookie 存在 9 处值冲突。配置后 20 个恢复作用域均保留，覆盖数为 0。
- 原生 UI 与规范桥接均为相同 9 个正文顶层字段，`userMessage` 均为相同 3 个字段；mode、modelAId、modelBId、v3 Token、cx 一致。
- 本次页面产生的 Token 长度 2617、cx 长度 20；只比较长度和等价性，不保存值。
- 原生准备 POST 与桥接 POST 各拦截 1 次并返回本地 418，真实生成请求为 0；约 21.2 秒完成。
- 这证明旧 Cookie 覆盖问题真实存在且请求准备字段已对齐；不能据此认定 Cookie 是全部 v2 故障的唯一根因，也不能宣称真实回答恢复。

## Read 性能与存储

同一批 18 个端点，内网及外网各 3 次读取，全部 HTTP 200。下表为低并发取样中位数，单位毫秒；样本量不足以作为正式负载测试 p95。

| 接口 | 集群内网中位数 | 公网直连中位数 |
| --- | ---: | ---: |
| health | 83 | 584 |
| ready | 159 | 661 |
| 管理总览 | 99 | 597 |
| 账号分页 | 92 | 609 |
| 注册任务 | 93 | 1008 |
| 注册计划 | 87 | 984 |
| 请求记录 | 103 | 1009 |
| 操作记录 | 96 | 1152 |
| 模型限制 | 110 | 1169 |
| 全模型目录 | 208 | 1749 |

- 全模型目录解压后约 1.56MB，gzip 约 110KB。传输体积和公网往返会增加可见等待；不将全部耗时归因于数据库。
- 内网基准也包含跨节点网络，不等于纯后端处理耗时。Hikari 取样 active=0、pending=0，最大连接获取约 1.9～2.7ms。
- PostgreSQL 观察到 7 个连接、无锁等待/长事务、deadlocks=0；无待处理 outbox，无运行超时的初次验证记录。
- Redis 取样约 613 个 key；未发现没有 TTL 的关键租约，evicted_keys=0、rejected_connections=0，AOF/RDB 状态正常。历史 error reply 累计不能全部归属当前版本。
- `pg_stat_statements` 未安装，没有取得全量慢 SQL 排名；没有据此做无证据的索引/数据库架构变更。
- 管理端轮询为 2～15 秒，React Query 已禁用窗口聚焦刷新、使用 15 秒 staleTime；本轮修复取消与超时，没有扩大轮询或新增后台生成。
- 基线服务端 HTTP timer：账号/请求/操作列表分别 6 个样本，平均约 19.47/29.05/17.42ms；与上述客户端中位数口径不同。它们进一步说明不能把公网往返耗时全部归因于 SQL。

## 厂商与鉴权审计边界

- 近期普通推理数据按 attempt 统计，重试会产生多条记录，不能当作最终请求成功率。审计窗口内 MiniMax 35/36 attempt 成功，MiMo 61/83，Grok 40/79；Arena Chat 8/21、Responses 0/3；GLM/LongCat 窗口内无普通推理。
- MiMo 存在下游取消及传输长等待；Grok 存在上游反自动化、不可用及传输失败。通用资源/取消修复会改善共享运行时，不等同于已消除厂商限制。
- 静态检查管理路由 ADMIN 保护、API key provider/model/feature grant、Responses owner scope 与既有契约测试；上线后再做双入口无密钥拒绝与六家 key 的目录隔离验证，不发送额外生成。
- 未测试任意客户端、全部模型及长时间并发；未恢复已停用厂商。

## 验证与发布状态

- Automation 全量：677 passed，含真实 Redis reservation 与 Node 页面脚本；1 条既有 Starlette/httpx 弃用提示。
- Ruff：全量 check / 140 文件 format check 通过。
- Web：ESLint、Next production build、TypeScript 检查通过。
- Backend：1.5.9 全量 612 项，607 passed、5 skipped（需显式开启的 Grok live interop），bootJar 通过；包含真实 PostgreSQL 调度集成、缓存/租约、权限与协议回归。
- 版本契约：源码 7 处及 `any2api-backend-1.5.9.jar` 均为 1.5.9；最新迁移 tag 1.5.0，无新迁移。
- 1.5.9 源码 `df78407`、CI `39147250465`、GitOps `8733c937794937067d988e73b5bcbf212f98bf5a` 已上线；运行验收发现预算仍为 3，未将本轮整体判定通过。
- 1.5.10 修正部署变量并占用新版本，配置契约测试旧部署失败、新部署通过；其余业务源码沿用 1.5.9。完整发布、capabilities/digest/API 与真实输出结果见以下生产回写。

## 1.5.10 生产验收回写（01:28 截止）

### 制品与部署

- 源码 `a78805e3b534a38c2434a9fe9a772e0321b8d5ba`，前序业务修复 `df78407ee15e6a576f43522cc4c40520ac323ff7`。
- [CI 39148123586](https://github.com/Prodigalgal/any2api/actions/runs/39148123586) 全部成功；Automation 为 **678 passed**（含部署变量契约/Redis/Node，1 条既有弃用提示），Ruff 141 文件格式检查通过。后端、Web 质量门禁及全部镜像构建、GitOps 更新通过。
- 本地 1.5.10 bootJar、Web production build/TypeScript、源码与 JAR 版本契约通过；业务代码的前序后端全量为 607 passed、5 个显式开启才运行的 Grok live interop skipped。
- GitOps/Argo revision `8eb4d14aa47cb091ca604bde4ce9763633d3065c`，`Synced / Healthy`。第一次控制面校验出现 Kubernetes API TLS handshake timeout，一次重试后完成；该错误不属于已验证的应用 API 失败。
- 不可变镜像公共后缀 `20261018-v1.5.10-release-a78805e3b534a38c2434a9fe9a772e0321b8d5ba`。日期为 CI 的 UTC 日期；运行时统一 1.5.10。以下 registry index/运行 imageID 与对应 arm64 子清单已核对。

| 组件 | Pod | registry index / 运行 imageID digest |
| --- | --- | --- |
| Backend | `any2api-server-847f77ffdb-2tj62` | `sha256:a21ff6862dbc490e2b94df0d3919d4fbf860e31692e71a7186da31f671b3b81f` |
| 共享 Automation | `any2api-automation-6dd599d884-ltgwb` | `sha256:354933aa6a88087d0d58a07db696b88ac40ba1fe8d11e7296e1756c6090f8c94` |
| Arena Automation | `any2api-automation-arena-c978d9cf6-f7jkr` | `sha256:5d827de0870e34e47707a1fbfdca6b877e53bd289facd39bd415d3f19365464e` |
| Web | `any2api-web-7b668b4b67-nhnhn` | `sha256:ca0af2cae1222cf4bbe5824f7d3612d7b83e3665fc69942e810a042d71e891ec` |

四个应用均 Ready、实际版本一致、当前实例 0 重启。DeepSeek/Qwen 为 disabled、0 副本。六家启用厂商的 INFERENCE/LIFECYCLE/REGISTRATION 均绑定原 Self Proxy 四节点；HE IPv6 候选池仍停用、无厂商绑定。

### OpenAI 契约与真实输出

`any2api.mnnu.eu.org`、`any2api-direct.mnnu.eu.org` 的 health/ready 均 200；无密钥 Chat/Responses 均返回 401/OpenAI error/request_id；六家分发 key 的模型目录均 200 且限制在自身 provider。健康、鉴权与目录检查没有产生生成请求。

随后发送 7 条小文本 Responses 验收请求：每家 1 条，Arena 首次落到其他账号并超时；确认优先会话已空闲后追加 1 条对照。只记录字符数、事件、耗时与 request_id，不保存回答正文。六家均至少有 1 条正文加 `response.completed`；不把单次成功扩展为全部模型、账号、工具、多模态或桌面客户端全量验收。

| 厂商/模型 | 客户端总耗时 | 正文字符 | 结果 | request_id |
| --- | ---: | ---: | --- | --- |
| MiMo / `mimo-v2.6-pro` | 7.751s | 4 | completed | `2bdac570-1442-4dd1-a00b-303550c87740` |
| LongCat / `longcat-flash` | 10.519s | 4 | completed | `19f916d3-6539-4254-a50e-ff7d55cac757` |
| MiniMax / `MiniMax-M2.7` | 7.360s | 4 | completed | `82178485-6b28-479d-97a8-b5e15734ac70` |
| Grok / `grok-3` | 18.258s | 5 | completed | `174b1626-1a5e-45fc-b60f-124ef77be4fa` |
| GLM / `GLM-5-Turbo` | 43.973s | 4 | completed | `6e6d6c03-6dd8-4eba-b360-ca892c80ba3a` |
| Arena / `Max`，首次 | 92.429s | 0 | response.failed，准备阶段 TimeoutError | `bdfc27dd-82a6-4da8-9162-aa270776b252` |
| Arena / `Max`，会话空闲后对照 | 13.752s | 4 | completed | `808e8b45-6ceb-44b5-b656-678bad373a8d` |

Arena 成功请求的后台记录确认账号为已有验证会话 `0c66aa4d-da59-41e4-9e6a-8c6d757009a9`，1 attempt、正文 4 字、Usage/Completed(stop)；后台耗时 11.436s、TTFB 11.102s。首条失败使用另一账号 `976b92e3-3f09-44ba-b840-8ebd62fd1b8a`，后台耗时 88.937s，未取得正文。这证明已验证会话在当前桥接与原四节点下可以完成公网 Responses；账号间准备状态差异仍存在，不能宣布 Arena 整池稳定。

### 资源、调度与 Read

- 01:25 资源取样：共享实例当前 **770MiB**、启动后峰值 **2133.1MiB / 3072MiB**；Arena 当前 **1294.6MiB**、峰值 **1410.4MiB / 3584MiB**。两个 cgroup 的 oom/oom_kill 均 0。
- 运行服务 capabilities 已确认共享 browser process tree budget **capacity=2**；Arena 仍为其既有独立预算 3。budget 是浏览器进程树数量，不等于系统总进程数；清理器本次取样没有误杀记录。
- 同一时点所有当前 PENDING 维护任务均没有 overdue，LEASED 没有过期；存在未来排定任务与历史 EXHAUSTED/SUPERSEDED 记录。发布观察窗已有 Grok 2 条、MiMo 8 条 action 延后为 `KeepaliveDeferredRecentInference`，没有新增生成验证。
- 1.5.10 的 18 端点内网/公网各 3 次取样全部 200：内网常见中位数约 83～132ms，请求记录本次 249.9ms、全目录 213.3ms；公网常用管理读取约 0.44～0.46s、模型限制 0.641s、全目录 **1.043s**（解压约 1.55MB）。不能用这些小样本声称已经完成并发压测或证明因果改善。
- 公网取样结束时 Hikari active=0、pending=0、当前 max acquire 1.22ms；数据库 1 个观察查询 active、5 个 idle，无等待/长事务。Backend 的 JVM `process.cpu.usage` 取样约 0.05。
- Redis 38 秒增量取样仅新增 1 条 ERR、WRONGTYPE 没有增加。隔离客户端复现握手 `MAINT_NOTIFICATIONS` 不支持后的回退，PING 成功；不能把累计约 5.6 万错误全部当作当前业务失败。历史其他错误来源仍保留调查边界。

### 发布后业务窗口与遗留

01:15:30～01:28:20 已写入记录：Arena 2 request/2 attempt/1 有效输出；Grok 9 request/13 attempt/8 有效输出，含 2 个 anti_bot_rejected、3 个 upstream_unavailable 失败 attempt；MiMo 6 request/6 attempt/4 有效输出，另 2 个 downstream_cancelled；MiniMax 8/8/8。GLM、LongCat 各有本轮 1 条成功。窗口同时包含受控验收和自然业务，且可能存在在途重试，不能当作完整最终成功率。

后续仍需收敛 Arena 旧账号的准备超时与优先会话被占用时的降级质量、Grok 上游拒绝、MiMo 下游取消的客户端/链路原因；Read 的公网往返、目录体积与高并发长尾仍需按独立证据优化。当前发布验证完成，长期稳定性、全部账号质量和全部客户端场景继续保留边界。

## 回滚与后续观察

- 本轮业务修复的应用回滚点为 1.5.8 镜像公共后缀 `20261018-v1.5.8-release-ec6ccf48ef90b8839af99759252a180ea1631b73`，以实际 registry digest 为准；正确的共享预算变量与 1.5.8 兼容，可保留为 2。1.5.9 制品也保持不可变，不能用镜像回滚代替核对配置是否生效。
- 不需要数据库回滚；不回收版本号；Arena 原四节点绑定保持，不恢复已停用 HE 池和 DeepSeek/Qwen。
- 上线后观察共享内存/重启、browser budget 等待、生命周期 overdue/leased、取消后的清理、真实正文与终止事件。短窗口无重启不代表长期 OOM 风险已消除。
- 若 Arena 仍返回官方人工验证要求，记录保留的原始状态/错误并继续定位，不通过重复换出口/账号或反复生成代替修复。
