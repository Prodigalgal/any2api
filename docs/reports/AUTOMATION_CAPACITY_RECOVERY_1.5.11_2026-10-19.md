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
- CPU、账号/厂商/出口限流保持。正常单副本内存 limits 合计约 15.375Gi；现有 namespace 20Gi 可容纳，两个 Automation 都是 maxSurge=0。Backend/Web 滚动额外副本也在现有配额内。
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

本地 Automation **686 passed / 3 Redis 条件跳过**；Ruff/143 文件格式通过；Backend test 缓存复用、1.5.12 bootJar 通过，Web lint/build/TypeScript 通过。版本/JAR、CI 与生产验收继续回写。1.5.12 回滚点为已在线验证的 1.5.11；更早 1.5.10 含本轮复现的长期停滞风险，只作为应急短期回退。
