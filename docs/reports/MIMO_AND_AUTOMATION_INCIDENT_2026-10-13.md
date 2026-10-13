# MiMo 桌面调用与 Automation 运行故障（2026-10-13）

## 当前基线与改造核对

源码及生产为 `0.30.0` / `516002ab336cfbc5f42f6366ffb5df460777bb34`，Argo 初查 Synced/Healthy。当前 Java/Spring Boot 负责 OpenAI 契约、租约、出口并发、状态和账本；Python/FastAPI 执行 WEB API 与浏览器 Runtime；Next.js 为管理控制台。近期新增注册节流/浏览器清理、高级模型探活保护、动态 probe model，以及代理绑定 UI/出口并发。此次 MiMo 实际通道为 API，公开协议为 Chat Completions。任务板历史源码 0.29.0 不能代替当前部署事实。Qwen 继续不纳入发布验收。

## “你好”的实际请求

用户提供时段为 10:00–10:09 UTC+8。`mimo-desktop` Key 对应以下四次普通 INFERENCE；模型为 `mimo-v2.6-pro`，均是 `stream:true`、`tool_choice:auto`，没有图片。

| UTC+8 开始时刻 | request_id | 耗时 | 结果 |
| --- | --- | --- | --- |
| 10:08:17 | `e18330c3-2d3e-4b0d-a111-916d5834694f` | 1597ms | context_length_exceeded |
| 10:08:19 | `64530d14-ce7c-4b2f-939a-06ffdac7c36c` | 1553ms | context_length_exceeded |
| 10:09:06 | `33b4415d-bb06-4723-9216-0c055836e549` | 1665ms | context_length_exceeded |
| 10:09:08 | `c6cf6ff8-e31b-4726-8134-5ba9ab4371b0` | 1357ms | context_length_exceeded |

第一、第三条的最后一个 text block 确实为“你好”（2 字符）。每次还携带 system **69,192 字符**、**31 个工具**；工具说明共 **64,292 字符**、参数 schema compact JSON 共 **26,434 字符**，完整工具 JSON 约 **94,358 字符**。入库 JSON 约 **168,029–171,161 字符**；这些是解析/存储后的数量，不是 token 数或原始 HTTP wire 大小。

四次 queue_ms=0，account acquire 为 86–94ms。MiMo WEB 返回长度拒绝，网关记录仅一个 Failed 终态：`param=messages`、`status=400`、`retryable=false`。`OpenAiResponseWriter` 对首个 3s 之前的快速失败使用 OpenAI HTTP JSON 错误，已经接受 SSE 后则使用流内失败终态；没有把错误正文作为模型答案。

同期 `99ff1d89-32f6-498a-a000-1fc50cdc7ff6` 运行 431316ms，来自另一 `any2api-mimo` Key、没有工具，末尾用户文本不是“你好”；该调用不能用于解释这次桌面请求。此前初查发现长调用后，已通过 Key 和输入结构重新归属。

只读检查当前 Xiaomi MiMo AI **26.1010.101546**：安装源码识别 `context_length_exceeded` 并进入 ContextOverflowError/overflow handling；本地当日日志没有匹配的请求错误，不能据此断言具体 UI 展示或重试行为。两个后续请求仍带相同 system/31 个工具，上下文没有降到 WEB 可接受范围。精简聊天与完整 Agent 的输入需求不同，客户端填写 1M/128K 不会提升 WEB 的实际限额。本次未修改客户端、原提示、权限、技能或密钥。

## 三项运行故障

- **Arena 存储**：旧 Pod `any2api-automation-arena-648bb56bc-hwvt4` 为 Failed/Evicted，明确消息 `Pod ephemeral local storage usage exceeds the total limit of containers 1Gi`，结束于 **2026-10-13 07:23:30 UTC+8**。替代 Pod 当前运行且零重启；10:24 节点磁盘空闲约 24.8Gi，Arena writable rootfs/日志约 360Mi，其中 `/tmp` 约 314Mi。挂载 `runtime` emptyDir 的 1Gi 上限与容器总 ephemeral 1Gi 是不同约束。
- **Automation 退出**：Pod `any2api-automation-5f9bdb4ffb-9hnv2` 于 **08:12:13 UTC+8**退出，exitCode=137、reason=Error，随后恢复，restartCount=1。没有 OOMKilled 标记，previous log 返回空；新容器 memory.events 的 oom=0 不能排除旧容器的历史内存故障。历史退出根因仍不完整。
- **探针超时**：同 Pod 的 readiness/live timeout 为 3s；初查事件分别累计 54/14 次 `context deadline exceeded ... awaiting headers`，最新约 10:11。10:29 附近一次 30 样本/约 60s 的容器内 live 检查全 200，最大约 49.2ms；低负载短窗口不能覆盖历史峰值。

## 0.30.1 修复候选

- `api_transport.api_stream`：旧 publish 把 put_nowait 作为线程回调提交，QueueFull 在事件循环发生，生产线程的 except 无法感知；队列饱和会丢数据/可能丢终止信号。改为提交异步 put 并等待有界队列腾出空间，停止时取消等待，保持事件顺序及 512 队列上限。
- 流取消的 response.close 移到工作线程，finally 保留生产端停止与任务回收；关闭失败仍传播，不把取消伪装为成功。尚未获得 response 的原生请求仍受原 HTTP timeout 约束，不能保证立即中断已进入原生库的连接建立。
- `BrowserProcessReaper` 的启动和周期 sweep 移到工作线程；shutdown 等待已启动扫描后再结束，启动/关闭扫描失败保留类型日志。扫描策略及浏览器预算保持。
- Arena overlay 仅将 ephemeral request **256Mi→512Mi**、limit **1Gi→3Gi**；CPU、内存、探针超时、滚动策略、DB/Redis/PV 保持。增加容量不等于已证明长期没有临时目录增长，需继续观察。
- 旧实现三项回归失败：有界队列丢事件，慢连接关闭/启动扫描拖慢真实 ASGI health。修正后加取消、关闭失败、周期扫描和 shutdown 验证，相关 **21/21**；完整 Automation **561 passed**，Backend test/bootJar、Web lint/build、Ruff/128 format、源码/JAR 版本通过。原始 before/after 证据在 `backend/build/runtime-hardening-v0301-*`，不保存用户原始 payload。

## 发布门禁与回滚

当前生产仍为 0.30.0；候选镜像、隔离及生产验收后在本报告追加事实。沿用七家桥接门禁、现有分发 Key、合成请求与失败账本；不进行高级模型健康探活、不修改 probe model、不扩展 Qwen。候选通过后提升同一 digest，并核对资源预算、探针、版本、重启与 SDK/账本结果。

直接回滚点为 0.30.0 四镜像 suffix `20261012-v0.30.0-release-516002ab336cfbc5f42f6366ffb5df460777bb34`，并撤回本次 Arena 资源 patch；历史 Failed Pod、重启与探针证据保留。
