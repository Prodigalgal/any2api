# Provider 生产运行态快照（2026-09-26）

## 核验范围

- 时间：数据库时间 `2026-09-26 02:55 UTC`（`10:55 Asia/Shanghai`）。
- 源码与制品：Backend、Automation、WEB 为 `0.21.0`；三套 Deployment 均运行
  `6cbe0251abffc1af665356d30a4b027ae423dd15` 镜像。
- 集群：Argo CD `any2api` 为 `Synced/Healthy`；三套业务 Deployment 均 `1/1`，
  业务 Pod Ready、重启数 0。
- 数据：仅执行只读 PostgreSQL 聚合；没有查询邮箱、凭据或错误明细。
- 口径：`INFERENCE` 与自动 `PROBE` 分开统计；所有请求数、生命周期事件数都是事件次数，
  不是不同用户或账号数。没有 `INFERENCE` 记录表示本窗口缺少这类证据，不表示 Provider 不可调用。

## 八家 Provider

| Provider | Java 通道 | ACTIVE 且启用账号 | 启用模型/目录模型 | 7 日 INFERENCE | 7 日 PROBE | 当前判断 |
|---|---|---:|---:|---:|---:|---|
| Arena | Runtime | 6 | 268/820 | 5/13 成功（38.5%）；最近 9 月 24 日，近 24 小时 0 条 | 4/11 成功 | 推理成功率低；历史错误含 anti-bot、账号不可用和请求错误 |
| DeepSeek | API、Runtime | 16 | 1/3 | 无记录 | 87/89 成功（97.8%） | 最近默认模型探针通过；探针 P95 约 94 秒，尚无本窗口普通推理证据 |
| GLM | API、Runtime | 20；另有 4 个 DEGRADED | 15/15 | 无记录 | 无 usage 事件 | 持久化逐模型探针最近结果为 10 READY、5 FAILED，最新探针时间为 9 月 23 日；近期证据不足 |
| Grok Web | Runtime | 1；另有 824 个 PENDING | 13/13 | 3/3 成功；均早于 9 月 24 日 | 85/331 成功（25.7%） | 仅一个 ACTIVE 账号；截至 9 月 26 日，13 个模型的最新探针均失败 |
| LongCat | API、Runtime | 29 | 5/5 | 4/14 成功（28.6%）；近 24 小时 0/6 | 350/439 成功（79.7%） | 推理和探针均不稳定；最新逐模型探针为 2 READY、3 FAILED |
| MiMo | API、Runtime | 60 | 14/14 | 9/9 成功；近 24 小时 2/2 | 1109/1187 成功（93.4%） | 当前推理证据最好；P95 约 35.8 秒，最新逐模型探针为 13 READY、1 FAILED |
| MinMax | API、Runtime | 9；另有 35 个 PENDING | 3/3 | 无记录 | 260/260 成功 | 三个模型探针均 READY，但近 24 小时有 132 次 daily_checkin 失败 |
| Qwen | API、Runtime | 21；另有 25 个 PENDING | 7/19 | 无记录 | 1/1071 成功（0.1%）；近 24 小时 0/100 | 当前严重降级；逐模型最新探针为 17 FAILED、2 READY，两个 READY 样本停留在 8 月 20 日 |

`grok` 和 `grok_console` 已在 0.21.0 退役；当前 Provider 注册表仅保留 `grok_web`。

## 生命周期与观测异常

- 近 24 小时 keepalive 失败事件：Arena 78、DeepSeek 240、GLM 313、Grok Web 29、
  LongCat 444、MiMo 845、MinMax 11、Qwen 277。MinMax 另有 132 次
  `daily_checkin` 失败。事件错误码大多为通用 `provider_operation_failed`，仅凭聚合数据无法定位根因；
  这些是尝试次数，不能当作失败账号数。
- scheduler 当前 `LEASED` 动作数为 0，已到期但仍 `PENDING` 的动作数为 0；
  `EXHAUSTED` 动作数为 Arena 12、DeepSeek 0、GLM 4、Grok Web 3、LongCat 4、
  MiMo 63、MinMax 35、Qwen 26。
- 另有 139 条 `LIFECYCLE` `operation_events` 仍标记为 `RUNNING`，但关联动作已是
  `PENDING`、`EXHAUSTED`、`SUPERSEDED` 或没有匹配项，且没有活动租约。这些是未收口的历史事件，
  不是当前正在执行的任务；管理端时间线可能因此显示过期状态。

## 结论边界

Pod Ready、模型探针和账号 ACTIVE 状态都不等于稳定的普通推理。当前有较新普通推理证据的只有
Arena、Grok Web、LongCat 和 MiMo；DeepSeek、GLM、MinMax、Qwen 本窗口没有
`INFERENCE` 记录。完整验收仍需逐 Provider 核对所选通道、非流式/SSE、模型与账号健康窗口。
