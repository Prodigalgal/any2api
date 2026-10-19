# 1.5.11 Automation 容量与积压恢复

## 发布前事实

2026-10-19 09:09 附近，最近一小时普通 Chat 已落库请求按 request_id 去重：Arena 29、Grok 38、MiMo 47、MiniMax 43，均无正文加 Completed 的有效输出。共享 Automation 的浏览器预算 2/2 被占用，却没有浏览器/driver 进程；reaper 最后推进在 05:54，09:13 取样停滞 11919 秒。两个 Automation 均无 OOM/重启。

09:22 数据库：612 个账号；531 条 PENDING keepalive（433 逾期）、79 条 PENDING 签到（1 逾期）、2 条有有效租约的维护；162 条操作 RUNNING 超过一小时。69,411 条 usage_events 为尝试历史，不是待执行聊天队列。

节点各约 23.3Gi 可分配，取样内存使用率 41%/51%。内存可扩容；清理/线程等待问题需要独立修复。隔离复现已证明旧代码中默认线程池占满时，浏览器清理和 reaper 无法推进；线上具体 Python 栈尚未取得，保留归因边界。

## 变更记录

### 同步工作、清理和前后台容量

- `blocking_work.py`：后台同步工作使用独立、实际执行并发为 2 的线程资源；异步排队可取消，取消后仍在执行的线程继续持有名额直到真正退出。保留 ContextVar 关联信息；工作取消信号可被同步浏览器等待读取。
- 所有厂商同步 offload 统一经过该 helper；前台沿用默认执行器，后台注册/保活/签到/模型目录同步进入后台资源。没有启用停用厂商。
- `browser_budget.py`：同步/异步名额等待默认最多 30 秒；同步等待识别取消，空闲驱逐共享等待时限。后台新建浏览器最多占 capacity-1（单容量实例允许 1），并优先让等待中的前台取得名额。
- reaper、浏览器残余进程回收、代理引用释放及流关闭使用独立清理执行器，取消时等待实际清理完成。保持代理池复用和闲时回收，不在每次请求后销毁隧道。
- GLM 生成器退出时传播工作取消，不再无界等待未取消的容量等待线程。

### 资源与自恢复

- 共享 Automation：memory request 1Gi→2Gi、limit 3Gi→6Gi，浏览器预算 2→4。
- Arena：memory request 1Gi→2Gi、limit 3584Mi→6Gi；浏览器预算仍为原值 3。
- CPU、账号/厂商/出口限流保持。正常单副本内存 limits 合计约 15.375Gi；20Gi 只足以容纳正常运行，不能覆盖正在终止的旧 Automation 与新 Pod 同时计费的滚动窗口。实际更新出现 FailedCreate/exceeded quota，后续补齐 namespace 配额，见下文。
- 内部 capabilities 提供线程工作数、排队、完成、取消未退出的计数；readiness 对 reaper/取消工作长期停滞返回 503，liveness 使用更长的恢复边界。全部检查仅访问内部状态，不请求厂商、不产生生成探活。

### 历史操作对账

- `OperationReconciliationScheduler`：每分钟最多处理 500 条、启动 30 秒后开始，对超过 30 分钟且没有匹配有效执行租约的 LIFECYCLE/REGISTRATION 操作记 FAILED / `execution_lease_missing`。
- 生命周期按 provider/entity/action/attempt/本代 claim 时间核对；注册按 job/provider/当前 attempt 区间核对，保留有效续租的长注册操作。当前操作、其他域和已终态记录保持。
- 事务 advisory lock 协调多副本，SKIP LOCKED 处理并发，重复执行幂等。无数据库结构迁移，不直接删除历史数据；正常既有保留策略仍生效。
- 发布前 162 条旧 RUNNING 的可变状态已 AES-256-GCM 加密备份至服务器管理 private/any2api/backups，完成解密回读校验；日志不记录凭据、提示或回答。

## 本地验证

- Automation：685 passed、3 个需 Redis 环境的集成测试 skipped；后续由 CI 的真实 Redis 补齐。新增线程池占满时清理/reaper 前进、取消工作持有名额、取消后退出浏览器等待、等待超时、前台容量、关联上下文、目录后台归类、GLM 流取消与内部健康测试。
- Ruff：全量 check/143 文件格式通过；1 条既有 Starlette/httpx 弃用提示。
- Backend：614 项，609 passed / 5 条显式开启的 Grok live interop skipped，127 suites；bootJar 通过。新增真实 PostgreSQL 的过期操作对账、有效所有权保护、前代/前次 attempt、近期操作保护和重复执行测试。
- Web：ESLint、production build、TypeScript 通过；没有 UI/业务视觉变更。
- 版本：源码 7 个契约值及 JAR 统一 1.5.11；最新迁移 tag 仍为 1.5.0。

## 兼容与回滚

OpenAI Chat/Responses、账号权限、代理绑定、注册计划、保活周期不变。不恢复周期生成探活、模型可用性判定和 DeepSeek/Qwen，不处理 RCM。应用及资源可回滚 1.5.10 不可变镜像及对应 GitOps 补丁；回滚不自动恢复已完成的历史终态，必要时使用加密状态备份按新终态条件核对恢复。

## 生产验收

### 1.5.11 上线与运行证据

- 源码 `0b3adcd6f5c47c56a99414f1c398aaab690fae64`；[CI 39186730599](https://github.com/Prodigalgal/any2api/actions/runs/39186730599) 全绿，真实 Redis 环境 **688 passed**；GitOps `d169e6dfed24b0de0b6282fcc2afbb6ec5e46706`，Argo Synced/Healthy。
- 09:45:30 对已经停滞的共享 1.5.10 实例做一次临时重启；已有自然流量中的 Grok/MiMo/MiniMax 各恢复完整输出。该窗口与新版本验收分开，不把重启结果归因于新代码。
- 09:51 起四个应用切换到 `20261019-v1.5.11-release-0b3adcd6f5c47c56a99414f1c398aaab690fae64`，实际版本均 1.5.11、digest 与 registry/arm64 清单一致、Ready/0 重启。两 Automation 的 cgroup limit 均 6442450944，request=2Gi；共享 capabilities capacity=4，Arena 保持 3。
- 双入口 health/ready 200，无密钥 Chat/Responses 均为 401/OpenAI error/request_id；六家 key 的目录隔离检查通过。DeepSeek/Qwen 0 副本，代理绑定仍为原 Self Proxy 四节点。
- 09:52:38 对账 **162 条**，超过 30 分钟/1 小时的 RUNNING 均降为 0。没有批量重置账号健康或删除待执行任务。
- 10:00:26 快照中维护 overdue **398**（09:22 为 434），执行在推进，expired lease=0。共享 reaper sweeps=19、cleanup completed=217；取消未退出的工作已归零。共享内存峰值 3771768832（约 3.51Gi），Arena 峰值约 1.39Gi；均无 OOM。
- 09:52 后已落库普通请求包含受控验收：Grok 11 request/14 attempt/8 有效，MiMo 14/14/11，MiniMax 9/9/9，GLM/LongCat 各 1/1/1，Arena 4/4/0。仍含 Grok/MiMo 下游取消与厂商错误，不能称为全部请求恢复。

受控小文本 Responses 每家一条，共 6 条；五家有正文加 response.completed，Arena 仍准备超时：

| 厂商 | 客户端耗时 | 有效输出 | request_id |
| --- | ---: | --- | --- |
| MiMo | 24.273s | 是 | bca3dfe2-db8a-44b3-abcf-756eaa426014 |
| Grok | 34.586s | 是 | afac5b7a-9141-4df3-bcd1-cd2b177300eb |
| MiniMax | 9.119s | 是 | 3dd8c8ee-cdba-4033-bc7d-dd4fcaec4146 |
| LongCat | 18.292s | 是 | d7cbd06c-b885-45bd-826d-373d73a2d2dd |
| GLM | 52.157s | 是 | 851369b6-3001-4150-81e4-60bc3c08cd69 |
| Arena | 90.227s | 否 | d953ddde-3e76-49f2-8f31-2e6d97efd520 |

Arena 该次选择 b1755123-d20b-4165-9d7c-460e88087dd5，后台 88.623s，TTFB 88.494s，错误保留 TimeoutError，不能推断为单一出口或验证码原因；其他请求还需独立跟踪。短窗口有效不代替长期稳定性、全部账号或桌面客户端验收。

### 1.5.12 MiniMax 消费者线程饥饿补丁

1.5.11 运行指标还显示共享 foreground completed 在几分钟内升至 6282，10:00 为 20720。MiniMax `_api_stream` 每读取一帧都把 Queue.get 放回生产者所在的默认线程池；线程池被生产者占满时，消费者无法取得线程排空有界队列。

新增单默认线程、100 数据帧（超过原队列容量 64）的确定性回归：旧代码超时；改为 asyncio.Event 跨线程通知、get_nowait 异步消费后通过。保留队列、单帧限界和所有返回/关闭语义，移除逐帧线程任务。该修正占用新版本 1.5.12。

本地 Automation **686 passed / 3 Redis 条件跳过**；Ruff/143 文件格式通过；Backend test 缓存复用、1.5.12 bootJar 通过，Web lint/build/TypeScript 通过。版本/JAR、CI 与生产验收见下文。1.5.12 回滚点为已在线验证的 1.5.11；更早 1.5.10 含本轮复现的长期停滞风险，只作为应急短期回退。

### 1.5.12 部署、真实输出与 Read 验收

- 源码 `a26668d09853fcb8146225a109f4c9cd2cf4a681`；[CI 39188142233](https://github.com/Prodigalgal/any2api/actions/runs/39188142233) 全绿，真实 Redis **689 passed**。GitOps 镜像更新 `e587062`，滚动配额补丁 `c0cf0f1aabcbfbb19bc839b8cf6c992b139fc3ba`；Argo Synced/Healthy。
- 10:11～10:12 四应用切换 `20261019-v1.5.12-release-a26668d09853fcb8146225a109f4c9cd2cf4a681`；运行版本均 1.5.12，registry digest、arm64 清单及实际 imageID 一致；Ready/0 重启。两 Automation 内存仍 request=2Gi/limit=6Gi，共享预算 4、Arena 3。
- 滚动配额：request memory 8Gi→12Gi、limit memory 20Gi→32Gi、limit CPU 10→12，CPU request 保持 3。正常 Pod CPU/内存不再增加；配额为新旧 Pod 重叠提供准入空间，不代表实际内存占用。1.5.12 配额生效前仍有 FailedCreate（最新 10:11:43）；后续版本化补丁避免重新漏配。
- 双公网 health/ready 200，无密钥 Chat/Responses 401/OpenAI error/request_id，六家 key 的目录隔离通过；DeepSeek/Qwen 仍 0 副本，所有启用厂商三作用域保持原 Self Proxy 四节点，独立 HE IPv6 池停用。
- 10:13 overdue=365；10:22:59 overdue=337，expired lease=0，超过 30 分钟/1 小时的 RUNNING 仍为 0。共享前台 completed=169（不再几分钟增加数万次），cleanup completed=281，reaper sweeps=24，取消未退出工作=0；共享内存峰值约 3.04Gi、Arena 约 1.87Gi，无 OOM。该短窗口不能替代长期稳定性。
- 10:19 公网 18 Read 端点各 2 次均 200；常用管理读取中位数约 401～506ms，全目录约 686ms、原始 1.55MB/压缩后约 110KB。数据库连接 pending=0，acquire 最大约 7.8ms；不能据此将公网耗时归因于数据库阻塞，也不能作为并发压测结果。

正确模型名的受控 Responses 验收每家一条，均有正文和 `response.completed`：

| 厂商 | 耗时 | 正文字符数 | request_id |
| --- | ---: | ---: | --- |
| MiMo | 4.343s | 4 | 716c3f70-287c-480b-8aa0-645efe7c2bd2 |
| MiniMax | 11.914s | 291 | 52aa728b-97a9-45c4-8eaf-17d698878716 |
| Grok | 36.296s | 4 | 48937f6b-6050-4c69-ac28-d29a243c657d |
| LongCat | 13.120s | 4 | ade2552b-9a99-4f5c-b394-ff7281eb7965 |
| GLM | 47.146s | 4 | 88d09bfe-bc32-4016-8b86-e8f91eb368c7 |

MiniMax 额外较长正文验证多帧流。首次诊断脚本重复拼接厂商前缀，错误请求 `7469bac9-5134-4a14-a669-890e80b8e1d9` 返回上游错误；这是验收输入错误，已保留记录并修正脚本，不能计作正确模型的系统回归。没有据此更改厂商载荷或追加周期探测。

### 新 Edge 会话与 1.5.13 路由修正

用户确认本机 Edge 可正常聊天，现场页面确有完整回答。会话身份与服务器 116 份 Arena 凭据均不匹配，前几次受控检查在写入前停止，没有覆盖原优先账号。随后按已授权的会话复用范围使用原导入接口独立入库：`0bb13730-997e-4488-83e3-8df724d29752`，初始 PENDING/disabled，priority=100，保持原 Sophia 四节点亲和。只保存 Arena 域 Cookie；未复制原账号密码、邮件凭据、Google 登录态或私密正文。Cookie 通过一次性 loopback 表单在内存中传递，备份为 AES-256-GCM/HKDF-SHA256 并解密回读校验，位置为服务器管理 private/any2api/backups 的 `arena-verified-new-session-v1512-20261019T022148Z.aes.json`。

首次显式入库验证调用旧 `POST /accounts/{id}/probe`，网关约 15 秒取消，02:22:27～02:22:42 的唯一检查记录成为 `FAILED / InitialVerificationInterrupted`，账号仍 PENDING/disabled。网关已有两个 exact 长请求匹配，但遗漏该兼容路径；管理台未选模型时也走旧路径。本次没有删除检查、重新消耗入库预算或启用周期生成。

因此占用 **1.5.13**：管理台默认/指定模型统一走既有 `/account-probes`；旧 UUID `/accounts/{id}/probe` POST 增加 300 秒超时。JSON patch 使用 test 校验原规则位置/预算，避免错误扩大全部管理路径；CI 同步带 target 的路由补丁与 namespace quota。现有 OpenAI、管理 API 和权限保持，无数据库迁移。

本地 Automation **694 passed / 3 Redis 条件跳过**，8 项新增路径/发布脚本幂等回归通过；Ruff/144 文件格式通过；Backend test 缓存复用、1.5.13 bootJar、Web ESLint/build/TypeScript、源码 7 值与 JAR 版本契约通过；对实际 GitOps checkout 的完整 Kustomize 渲染 23 个资源通过，普通 Read 与流式路由保持。源码 `bae94d4f6139bb54d3691a6f0a4768b659b731c2`，CI/部署及定向 Arena 生成结果见下文。

10:28:46 对新账号做一条定向 Max 验收，经当前 Arena Automation 的受保护 Action API（没有另起 Python 浏览器、没有修改服务代码或重新探活），使用原 Self Proxy/Sophia 固定亲和和生产浏览器/代理池；48.103s 返回上游 **403 / recaptcha_v2_required / recaptcha validation failed**，0 字符、无完成帧。内部 HTTP 200 是 NDJSON 传输成功，不是生成成功。保留 request_id `23dd9b66-544a-4fe6-b28b-97f36709d61f`、验证码状态元数据和终态，不保存回答/Token。账号仍未启用，唯一入库检查未重置。

当前结论：Edge 页面有真实回答，但 Arena 域 Cookie 迁移到服务器后未取得完整回答；不能把问题简化为旧 Cookie、单一出口或只要扩容即可恢复。当前工具未导出 Edge 的 localStorage/其他浏览器状态，本次仅验证 Cookie 迁移及生产 Runtime；环境、完整验证上下文与账号风控的具体贡献尚未隔离。没有自动解图形验证、切换代理池或伪造成功状态。

### 1.5.13 最终发布与运行验收（10:41）

- 源码 `bae94d4f6139bb54d3691a6f0a4768b659b731c2`；[CI 39190180615](https://github.com/Prodigalgal/any2api/actions/runs/39190180615) 全绿，真实 Redis **697 passed**，Backend 和 Web 质量/镜像构建通过；GitOps `af27df0a1f95a98938e58159a3c071ed4186e13d`，Argo **Synced/Healthy**。源码/JAR 均为 1.5.13，最新数据库迁移 tag 仍为 1.5.0。
- 四应用实际版本 1.5.13、Ready/0 重启；registry digest、arm64 清单和实际 imageID 一致；旧 Pod 已退出。不可变镜像统一后缀 `20261019-v1.5.13-release-bae94d4f6139bb54d3691a6f0a4768b659b731c2`。

| 组件 | Pod | Registry / running digest |
| --- | --- | --- |
| server | any2api-server-5b466685dd-jcc8x | sha256:126026705095f219612abcee9ee6ef295b1c6c030c576aa3717a9df9498ea82c |
| automation | any2api-automation-6bc76db64f-bblct | sha256:9f49d94e96121349ef96ccf5907ccbefa56eba864c9835913dd0252432c9d376 |
| automation-arena | any2api-automation-arena-6bd4f49cd4-snxbs | sha256:1acafa5ad3c377a389981f8ede39b14bd8eade38602936ee82cff7204268f652 |
| web | any2api-web-7f5df945cc-4cfrl | sha256:0fa95042bf0e2578d83a62a718362e92fb621e84dc2b7f3ae36e345820244870 |

- **部署配置**：`namespace-runtime-quota.yaml` 的 request memory=12Gi、limit memory=32Gi、limit CPU=12 已生效；两 Automation Pod 仍各 request=2Gi/limit=6Gi，共享 browser capacity=4、Arena=3，CPU request/limit 保持。10:35 起滚动更新 **FailedCreate=0、quota error=0**。实际 GitOps checkout 已恢复至已发布 main，临时渲染修改已清理，未推送本地占位镜像。
- **管理接口**：`admin-account-probe-timeouts.json` 对 HTTPRoute generation=7 生效，Accepted/ResolvedRefs 均 True，验证匹配增至 3，request/backendRequest 均为 300s；普通 Read 路由未扩超时。`web/src/lib/api.ts` 默认/指定模型均调用现有 `/account-probes`。对同一已失败检查请求新旧两接口，各返回 200 / performed=false / InitialVerificationInterrupted；检查 claim 和完成时间不变，**0 次生成**，新 Arena 账号仍未启用。
- **公网契约**：双入口 health/ready 200、Chat/Responses 未授权 401/OpenAI error/request_id、六家 key 目录隔离通过。18 Read 端点各 2 次均 200；常用读取中位数约 420～546ms，全目录 650ms；模型限制读取冷样本 952ms（两样本中位数 688ms）。数据库连接 pending=0，acquire 最大约 19ms；这是短样本而非并发压测。
- **输出**：只追加一条 MiniMax Chat 验收，4 字符 + finish_reason=stop + [DONE]，10.328s，request_id `8af48195-4cd9-4fd9-a2ff-7c6cf8b8b6b5`。五家 Responses 的本轮受控验收来自 1.5.12，1.5.13 未改生成代码、未重复五家全测。10:37:46 后至 10:41:23 落库普通请求：Grok 5 request/6 attempt/5 有效，MiMo 2/2/1（另 1 下游取消），MiniMax 3/3/2（另 1 下游取消，包含上述 Chat）。无当前 LongCat/GLM 新业务样本，不将旧样本标成新版本逐家实测。
- **恢复状态**：维护 overdue 从 434→398→365→337→321→283→**276**，expired lease=0，超过 30 分钟/1 小时 RUNNING=0。共享/Arena reaper 均 12 sweeps，取消未退出工作=0；共享 proxy created=4/reused=33，继续复用而非逐请求销毁。内存峰值分别 2925.6MiB、1355.9MiB / 6144MiB，OOM/当前 Pod 重启均为 0。

**兼容性**：OpenAI/管理 API 权限、数据库结构、厂商载荷、账号检查唯一预算保持；保留旧单账号验证 API，只修复其超时路由。DeepSeek/Qwen 继续 0 副本；启用厂商三作用域仍走原 Self Proxy 四节点，独立 HE IPv6 池保持停用。没有恢复周期生成探测或模型可用性判定。

**遗留**：Arena 新会话在生产 Runtime 仍为官方 v2/403，当前未恢复；仅 Cookie 迁移不证明完整验证上下文可移植。276 条维护过期任务仍在后台消化；MiMo/MiniMax 仍有下游取消记录，尚不能认定全部客户端和全部账号长期稳定。没有用批量删除任务、重置健康或重复入库验证掩盖这些边界。

**建议回滚点**：应用回滚 1.5.12 对应不可变镜像；保留 6Gi Pod 预算、32Gi namespace 配额、300 秒兼容验证路由，以及已完成的历史对账终态。1.5.12 Backend 已支持新旧两验证接口，保留路由兼容；新导入 Arena 账号仍 disabled，不回滚或覆盖其他账号凭据。加密备份仅用于明确的单账号/操作状态恢复，不能无条件覆盖其后状态。

**本地证据**（忽略目录、仅元数据）：`production-v1513-verified-20261019.json`、`production-v1513-public-20261019.json`、`production-v1513-registry-digests-20261019.json`、`production-v1513-control-contract.json`、`read-v1513-recovery.json`、`recovery-v1511-v1513-final.json`、`minmax-public-v1513-chat.json`、`arena-imported-transport-v1512.json`；会话导入截图不含 Cookie/Token/账号信息。
