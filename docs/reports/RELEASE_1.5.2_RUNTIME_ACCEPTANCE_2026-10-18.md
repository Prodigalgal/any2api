# 1.5.2 浏览器进程保护、代理池与 Arena 固定 IPv6 发布验收

时间统一为 UTC+8。本文区分本地回归、部署健康、代理连通和普通推理，页面 200/目录返回不代表模型推理成功。

## 发布事实

- 源码：`48718c9297707a02e892fdb7f3bdb040faf95ba0`。
- [CI 39116067079](https://github.com/Prodigalgal/any2api/actions/runs/39116067079)：三项质量门禁、六个 ARM64 不可变应用镜像与 GitOps 更新全部通过。沿用原浏览器基础镜像，应用制品继续生成 SBOM/provenance。
- GitOps：`23e93e6b1120c29b14e316d7be0fb704cc69c2ec`，17:42:49 提交。
- Argo：`Synced/Healthy`、同步 `Succeeded`。四个运行组件的镜像、应用实际版本均为 1.5.2，Ready、零重启，运行 digest 与 registry 核对一致；DeepSeek/Qwen 保持 disabled/零副本。
- 规格：[执行计划](../requirements/RELEASE_1.5.2_BROWSER_PROXY_OWNERSHIP.md)。

## Changelog

### 浏览器进程清理

- `browser_budget.py` / `process_identity.py`：浏览器租约登记 driver 的 PID 与内核启动时钟，资源释放时解除登记；各浏览器启动路径登记持有的进程根，覆盖通用 Runtime、MiniMax、GLM 和现有 Qwen 路径。
- `process_reaper.py`：保护仍被租约持有的进程树；年龄改为 `/proc/uptime` 与 `/proc/<pid>/stat`，不再使用目录 mtime。发信号前及升级 SIGKILL 前检查 PID 身份；浏览器识别限制为实际可执行文件，driver 限定 node/nodejs，避免路径/参数包含名称造成误判。
- 保留孤儿/无归属超龄进程清理、请求 deadline、容量门禁和阻塞扫描在线程执行；内部资源接口增加 `managed_process_roots` 计数。

### 代理生命周期与复用

- `lifecycle/proxy_tunnels.py`：隧道归属于独立有界池，以完整 VLESS 节点配置作为身份；同一节点并发首次获取只启动一次，后续保持同一本地端口。
- 默认每个 Automation 进程最多保留 16 条，最后一个引用释放后进入空闲，300 秒后回收；容量满时淘汰最早空闲隧道，不淘汰活跃持有者，等待超过 10 秒返回明确错误。维护周期为 15 秒，退出时等待维护并关闭隧道。
- `official_browser.py` / `minmax_browser.py`：请求和浏览器分别持有物理隧道引用。请求结束释放流量并发 reservation，浏览器关闭释放其引用，空闲缓存不占请求额度；出口或凭据变化仍重建浏览器。
- 崩溃隧道重新建代，旧引用晚释放不能删除新代；启动失败、取消、关闭和重复释放有回归。`transport_support.py` 在取消线程中的获取操作后回收已取得资源，并等待异步退出清理。
- `proxy.py`：Redis 独占租约为 string，原释放 Lua 先执行 SISMEMBER 会触发 WRONGTYPE；现先检查 key 类型，按 string/set 分别释放且校验独占 owner。CI 增加隔离 Redis 集成门禁。
- 统一 `browser_proxy_options`，把 HTTP 代理 URL 内的认证信息转换为浏览器的 `server`、`username`、`password` 字段，保留 URL 转义与 IPv6 host。

新增配置沿用 `ANY2API_AUTOMATION_` 前缀：

| Key 后缀 | 默认值 | 含义 |
|---|---:|---|
| `DYNAMIC_PROXY_TUNNEL_POOL_SIZE` | 16 | 每进程物理隧道容量 |
| `DYNAMIC_PROXY_TUNNEL_IDLE_SECONDS` | 300 | 无引用后的空闲保留秒数，可设 0 关闭空闲保留 |
| `DYNAMIC_PROXY_TUNNEL_ACQUIRE_TIMEOUT_SECONDS` | 10 | 容量/同节点启动等待上限 |

### IPv6 服务与 Arena 绑定

- 找到 `168.138.40.52` 原 `/root/proxy_pool.py`，监听 `10800`，HTTP/SOCKS5 认证代理使用 HE `2001:470:36:3ea::/64`；两次独立连接出口不同，说明按连接随机换 IP。原脚本、监听与进程均保留。
- 用户明确授权改造后，部署 `deploy/ipv6-proxy/ipv6_proxy.py` 与 `any2api-ipv6-proxy.service`。新入口最终仅监听 Tailnet `100.64.0.2:10801`，转发与认证走加密私网，不新增公网监听。
- 4 个认证节点分别固定一个 IPv6，服务重启不变；仅允许公网 IPv6 的 80/443，拒绝私网/回环/组播、限制 128 并发、握手/建连/空闲超时，剥离转发中的代理认证头。systemd 使用 DynamicUser、credential 文件、128 MiB 内存上限与退出重启管理。
- Windows 与实际 Arena Pod 各验证每节点两次独立连接，全部地址稳定。私网收敛后再次从 Pod 验证四节点一致。
- 原始 HTTP 访问 Arena 为 Cloudflare 403；单独临时浏览器 Pod 使用同一固定出口访问得到 HTTP 200、正确标题和官方 `/_next/` 资源，耗时 49.7 秒。临时 Pod 已删除，该验证没有发送模型生成请求。
- 新代理池 `Arena IPv6 - Oracle 40.52`，ID `41a37937-ea60-47d4-bdcf-664867f3460f`，后端 AES-256-GCM 加密保存。17:46:10 在 1.5.2 Ready 后启用，Arena 三作用域 `INFERENCE/LIFECYCLE/REGISTRATION` 已绑定；SQL 与管理 API 复核通过，其他厂家维持原 Self-hosted Oracle 四节点。
- 凭据与原绑定备份仅位于服务器管理 private 目录；未提交或输出认证值。新服务源文件 SHA-256 `35cd674861d0a994fcce6b6e628511955a6418b7a02ec224a6bc8f8ff8f9a534` 与仓库一致；采样 Active/running、NRestarts=0、内存约 14 MiB。

## 验证结果

- Automation：601 项通过，包含真实 Redis 的独占/多 owner/不存在或异类 key 释放；Ruff check、134 个 Python 文件 format check 通过。版本/JAR 契约均为 1.5.2。
- Backend：Gradle test/bootJar 成功；601 项中 596 通过、5 项显式 opt-in Grok 在线互操作跳过。CI 全量门禁通过。
- Web：lint、生产 build、TypeScript 检查通过。
- 专项覆盖：受管进程超过 600 秒仍保留、解除持有后清理、孤儿清理、PID 重用、真实开始时间、20 次请求只启动一条隧道、浏览器跨请求持有、取消、容量等待与淘汰、进程崩溃、旧引用晚释放、创建/导航失败清理、认证与私网目标拒绝。
- 双公网入口 health/ready 为 200；Chat/Responses 未鉴权调用为 OpenAI JSON 401 且带 request_id；六家独立 Key 的目录均为 200 且只返回本厂家模型。此项新增生成请求为 0。

### 运行镜像核对

镜像统一后缀：`20261018-v1.5.2-release-48718c9297707a02e892fdb7f3bdb040faf95ba0`。

| 组件 | Pod | OCI index / 运行 digest |
|---|---|---|
| Server | `any2api-server-58f966949f-pvxlj` | `sha256:a0418f4b958b45a9434973c353354d316b188fdd472bec20ca2d903add575517` |
| Automation | `any2api-automation-94dfbd5bd-xwh7x` | `sha256:0c247fe0caf64b17a189c4aaa9fc1b71acbe4b27f52bdbfb3910e116d5263693` |
| Arena Automation | `any2api-automation-arena-589cb444f7-mw5fm` | `sha256:30b11dfa020b75719c72e3fcbff6f1586dc182d45188dcc8173efc4ac5a7ca75` |
| Web | `any2api-web-66f75ff95b-vrsnw` | `sha256:908795a897546d1000028bcd68580993b7a05981007701fccada09f0766a809d` |

### 发布后首轮资源采样

- 共享 Automation：3 条隧道创建、7 次复用、2 条空闲；浏览器预算使用 3/3、受管进程根 3。
- Arena Automation：2 条切换前建立的 VLESS 隧道仍按请求/浏览器持有与空闲规则退出，未强行中止在途请求；浏览器预算使用 2/3、受管进程根 2。
- 两实例容器内 `/health/live` 各 3 次采样，2.63–12.45 ms。此数据不能代表外部推理总耗时或所有后台 Read 接口性能。
- 两实例清理器记录的孤儿/超龄清理次数均为 0；运行注册数证明进程保护已启用。超过 600 秒保护以专项回归验证，不能以短时运行样本替代长期稳定性结论。17:49 的后续采样中，共享实例创建 4 条、复用 18 次。

## 兼容、风险与回滚

- 公开 OpenAI API、数据结构与数据库迁移不变，客户端无需改接入方式。后端仅统一补丁版本并执行回归；DeepSeek/Qwen 继续停用，RCM 未修改。
- 固定 IPv6 与前端加载验证不能证明 Arena 模型生成恢复；需以切换后实际 `INFERENCE` 结果验收，账号登录与 reCAPTCHA v2/v3 仍可能拦截。
- 空闲保留用少量常驻内存换启动延迟，容量与 TTL 可按实际资源调整；本轮尚未给出端到端推理延迟改善百分比。
- 本次滚动期间出现短暂 CPU quota FailedCreate，以及容器启动/旧 Pod 退出时的探针连接拒绝，均自动恢复，最终新 Pod 零重启。并发滚动的配额余量仍需留意。
- 回滚时先按 private 中的原绑定备份恢复 Arena 至 Self-hosted Oracle 三作用域，再使用 1.5.1 原不可变镜像组合；1.5.1 缺少本次认证字段映射，不能保留新代理绑定直接退镜像。新 IPv6 服务待在途请求结束后可独立停用。无数据库回滚步骤，禁止覆盖已发布镜像。

## 普通推理验收状态

按请求开始时间筛选 17:46:11 之后、截至 17:52:02 已完成的自然流量，在途请求不计；本轮未新增模型生成探活。样本均为 Chat Completions，Responses 此轮为契约/回归验证，未新增生成实测。

| 厂家 | 自然请求/尝试 | 有效正文 | 结论 |
|---|---:|---:|---|
| Arena Max | 2 / 2 | 0 | reCAPTCHA v2 仍阻断，未恢复推理 |
| MiMo mimo-v2.6-pro | 2 / 2 | 2 | 159/181 字符正文，Completed(stop)，推理事件单独记录 |
| Grok grok-3 | 1 / 1 | 1 | 241 字符正文，Completed(stop) |

- Arena `daa79028-b872-4faf-907c-14d7f4df6f63`：耗时 145.664 秒，日志明确进入 chat 的 v2 challenge 后 TimeoutError，最终 504/provider_upstream_error。
- Arena `9f80503d-2980-4749-be51-7d7696fa6d54`：耗时 109.483 秒，anti_bot_rejected，`recaptcha_v2_required`。
- MiMo 请求 `98ce74b1-934a-421d-a0a5-db00d4627cc2`、`132c2347-5ef8-48ec-9468-b816b9813cad`；Grok `6efd4824-3d28-48de-8c03-f4376c6f0ff9`。只保存事件类型与长度，不保存提示或答案正文。
- 新 IPv6 入口未提供代理认证时，生产 Pod 实测返回 407。固定出口已接入，但不能替代 Arena 的 v3 评估/v2 验证；用户随后要求继续定位该回归，另行推进。
