# MiMo 桌面调用与 Automation 运行故障（2026-10-13）

## 当前基线与改造核对

初查源码及生产基线为 `0.30.0` / `516002ab336cfbc5f42f6366ffb5df460777bb34`，Argo 初查 Synced/Healthy。当前 Java/Spring Boot 负责 OpenAI 契约、租约、出口并发、状态和账本；Python/FastAPI 执行 WEB API 与浏览器 Runtime；Next.js 为管理控制台。近期新增注册节流/浏览器清理、高级模型探活保护、动态 probe model，以及代理绑定 UI/出口并发。此次 MiMo 实际通道为 API，公开协议为 Chat Completions。任务板历史源码 0.29.0 不能代替当前部署事实。Qwen 继续不纳入发布验收。

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

### 候选与范围例外

- Source `a766eadd0b51838ae35cf582fbde372dd5368f0f`，CI [38339184576](https://github.com/Prodigalgal/any2api/actions/runs/38339184576) 全部质量及镜像构建成功。四个隔离候选版本/digest 一致，Ready、零重启，验收后删除本次拥有的 4 Pod、2 Service、1 ConfigMap。
- 原七家矩阵 **39/49** 项通过；Grok、GLM、LongCat、MiMo、MiniMax 各 **7/7**，对应 **25 次普通 INFERENCE / 25 attempts / 25 successes**。Arena、DeepSeek 各有两次 `503 model_unavailable`，在上游前拒绝，没有伪造普通推理账本；其依赖步骤未通过，原始失败报告保留。
- Arena 同时具有 118 个可选账号，但缺少稳定的新鲜模型健康证据；已配置 `gemini-3.5-flash-lite` 的一次显式基础 PROBE 结果为 `upstream_timeout`，240016ms。未对 Sonnet/Max 做健康探活，未修改 probe model。DeepSeek 为 15 EXPIRED + 1 DEGRADED，均 enabled=false；近期自动 keepalive 失败为 `empty_model_response`，注册失败在 form_ready/transport，未修改或重新启用账号。
- 用户明确选择 **“先发布稳定性修复，再处理两家故障”**。因此此次发布只以五家完整候选验收作为桥接门禁，Arena/DeepSeek 保留故障项，不将七家报告改成成功。Qwen 继续排除。

### 生产发布与验证

- Source 已快进 `main`；[GitOps `a42d1e6`](https://github.com/Prodigalgal/ircs-prod-config/commit/a42d1e642b1b35f1ef7c87429523db7048349a61) 原子更新四个镜像和 Arena storage patch，继承发布前最新 GitOps 父提交，不覆盖其他应用变动。未重建候选制品。
- 生产四组件安装/API 版本为 **0.30.1**，镜像 suffix `20261013-v0.30.1-release-a766eadd0b51838ae35cf582fbde372dd5368f0f`；实际四 digest 与隔离候选完全相同。Argo **Synced/Healthy**，新 Pod Ready、零重启，公网 healthz/readyz 均 200。
- Arena 实际 request **512Mi**、limit **3Gi**；CPU 2、memory 3584Mi、三个 probe timeout=3s，RollingUpdate maxSurge=0/maxUnavailable=1 均按原生产契约保持。旧 Evicted Pod 保留。
- 五家公网官方 SDK **35/35**，Chat/Responses、JSON/SSE、system/skill/早期历史、严格函数、客户端工具结果回传、previous_response_id 和资源读取/自有状态清理通过；对应 **25 次普通 INFERENCE / 25 attempts / 25 successes / 0 failures**，没有 SDK 自动重试。报告 `web-bridge-production-v0301-accepted-five.json` 与 `-ledger.json` 记录精确 request_id。
- 普通 SDK 验收期间（**11:52:37–11:53:39 UTC+8**），两个新 Automation Pod 的 live/ready 共 **120 次检查全部 200**；共享 Automation 最大 **46.909ms**，Arena 最大 **52.564ms**，新 Pod Warning events 为空。该短窗口不能证明后续高峰或长期临时目录增长不会再次出问题。
- 公网生产旧版本的最小“你好”合成 Chat/SSE 复测成功：无 system、无工具，首段正文 **25.831s**、完整 **31.437s**，有正常 finish/usage；它不代表桌面 31 工具的完整 Agent 上下文已可被 MiMo WEB 接受。
- 生产正在服务实时流量；瞬时 concurrent>0 本身不作为发布失败。安装身份、资源和健康检查与精确合成请求账本分别核对，不人为清空用户请求或重置熔断状态。

### 遗留风险与后续优化

继续定位 Arena 上游基础模型超时、DeepSeek 空响应及账号状态转换，按实测协议和异常路径修复。完整桌面 Agent 上下文仍超出 MiMo WEB 能力，客户端 overflow/UI 行为尚未得到实机复现证据；不声称本次运行稳定性补丁解决了该上下文问题。历史 Automation exit137 的唯一根因仍未证明。新的可部署代码或配置候选必须继续占用新版本，不能覆盖 0.30.1 镜像。

直接回滚点为 0.30.0 四镜像 suffix `20261012-v0.30.0-release-516002ab336cfbc5f42f6366ffb5df460777bb34`，并撤回本次 Arena 资源 patch；历史 Failed Pod、重启与探针证据保留。
