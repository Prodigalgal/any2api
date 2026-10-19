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

待 CI、GitOps、运行版本/资源、真实输出、维护推进和历史对账完成后回写。短窗口有效不代替长期稳定性、全部账号或桌面客户端验收；Arena 的官方 v2/账号差异继续独立记录。
