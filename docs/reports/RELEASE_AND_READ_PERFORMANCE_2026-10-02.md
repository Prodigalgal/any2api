# OpenAI agent 发布、真实验收与 Read 性能（2026-10-02）

## 范围与结论口径

用户已授权提交、部署和测试。本轮沿用 GitHub Actions → `ircs-prod-config` → Argo CD 的生产发布链路，先发布协议升级 0.24.1，再发布真实验收发现的工具解析/Read 修复 0.24.2、Chat 工具历史修复 0.24.3 和包装版本修复 0.24.4。所有运行态证据只对应本报告日期；健康、文本推理、工具闭环和图片能力分别判断。

用户最新边界：对外只使用 **OpenAI Chat/Responses 的常用接口**，上游各厂商均按 **WEB 请求 / Browser Runtime** 桥接。重点为 `/v1/models`、`/v1/chat/completions`、`/v1/responses` 的多轮文本、JSON/SSE、function 工具与结果回传，以及按模型能力支持的图片。工具在调用方执行；本项目不承接客户端 shell/patch 策略或 Codex 专属模型目录。namespace/custom 等已实现扩展保留，不要求所有厂商支持，不再规划全量 OpenAI/厂商官方 API。

0.24.0 是之前的本地验收候选，其 [验收记录](OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)保留历史结论。本报告记录后续部署，不将受控 fixture 结果替代真实厂商验收。

## 发布与运行证据

| 项目 | 0.24.1 | 0.24.2 |
|---|---|---|
| 源码提交 | `a241c59870733ef3b55b47fd28eb685247b0fcf6` | `8d934bafeca1ea5c237867f04b85653b367f8d5a` |
| CI | [37011929616](https://github.com/Prodigalgal/any2api/actions/runs/37011929616)，success | [37016567168](https://github.com/Prodigalgal/any2api/actions/runs/37016567168)，success |
| GitOps revision | `39a59fb932b1d949e428fd17cd64ed78eea73359` | `3043e1262c585b178ac273d761d53828674ababa` |
| Argo CD | Synced / Healthy | Synced / Healthy |
| 应用部署 | server / automation / automation-arena / web 各 1/1 Ready | 四个应用各 1/1 Ready，核验时 restart count 均为 0 |
| Backend runtime | 启动日志 v0.24.1 | 启动日志 v0.24.2；启动约 30s |
| Automation API / Web | OpenAPI / package version 0.24.1 | OpenAPI / package version 0.24.2 |
| Liquibase | 032 两个 changesets EXECUTED，tag 0.24.0 | 36 changesets 已应用，no changesets to execute；无新增迁移 |

### 最终候选 0.24.4

- 源码提交：`b81886c4bb9b938df62bcaf6fe490594aaceff88`；前序 null-content 修复提交 `0bfacd37445f37239633d83453deb36c3dd293f2`（0.24.3）。
- [CI 37020984892](https://github.com/Prodigalgal/any2api/actions/runs/37020984892) 全部 quality / 四镜像 / update-gitops success；0.24.3 CI 为 [37019554190](https://github.com/Prodigalgal/any2api/actions/runs/37019554190)。
- 最终 GitOps revision `49b70b3184f982b2516814187ec5d805eaade05a`，Argo CD **Synced / Healthy**；0.24.3 对应 `116df49f01427c25c7dc3e25376c6b6a73401236`。
- 四应用 **1/1 Ready，restart 0**。Backend 启动 v0.24.4（约 29.8s）；Web package 0.24.4；两个 Automation Pod 的 `check_runtime_version.py` 均输出 **project / installed / API 0.24.4，PASS**。数据库无新增 changesets。
- 最终镜像 tag：`<component>-20261002-v0.24.4-release-b81886c4bb9b938df62bcaf6fe490594aaceff88`。

| 最终组件 / Pod | imageID digest |
|---|---|
| server / `any2api-server-56c67d9bff-5glh4` | `sha256:70015aaa9c4abd1dc9db15c403c7489bb9ad68f93632248458a30b24f74ad806` |
| automation / `any2api-automation-78cfdbb77c-2bzwl` | `sha256:b586188f7fb61dcca85a028276d094b13a3ed40e6ff49e9abe889cb562ab5e3a` |
| automation-arena / `any2api-automation-arena-6dc5bfb9b8-lhg98` | `sha256:9d48f1843ec52206f3f8c8bedd07e83e8376092efbf2b27e3e62b0ce05047f9c` |
| web / `any2api-web-6bc476bfb7-6b4sm` | `sha256:b8fd47890724ab7ebb41ca6676b5dd29b7fb3aca84edc2d42461a9ec0e92b2b2` |

最终 server / automation / web / Redis 在 `instance-20250708-1530`，arena / PostgreSQL 在 `instance-20251229-0833`。

镜像仓库：`docker.io/speedproxy/any2api`。每个镜像 tag 为 `<component>-20261002-v<version>-release-<full-source-sha>`，未覆盖旧 tag。032 的 tag 保留新增结构所属的业务版本 0.24.0。

### 0.24.2 实际 Pod 与 imageID

| 组件 / Pod | imageID digest |
|---|---|
| server / `any2api-server-6f4bf9b689-rj5md` | `sha256:abce395c4a0bd77505350da10d20c532c632731ec715f00976c12f9cce6bfdd7` |
| automation / `any2api-automation-5bcdb7bc5f-8jh46` | `sha256:a2d791c4a87bb9e41a759808c197a787f1fa0b99f7373a607748b9934d2fee9a` |
| automation-arena / `any2api-automation-arena-774b67bb78-ts9sh` | `sha256:e3efeedc5152d303105dba7d940d0cbd8b1ef3a996b051dc6112e54b7ce9e08d` |
| web / `any2api-web-6459d7b68f-6kdbr` | `sha256:dddf91902e0439943eeb33f5fbb47e74b098db1342d1493a8139bd9ba880a1fc` |

0.24.2 应用位于 `instance-20250708-1530`，PostgreSQL 位于 `instance-20251229-0833`；Redis 与 server 同节点。后续 rollout 的 Web 可由调度器放到另一个节点，应用与数据库主链路的跨节点关系未改变。本轮没有迁移数据库或改变 Pod 放置策略。

**已修复的包装缺口**：0.24.2/0.24.3 的 Automation HTTP version 正确，但 `importlib.metadata.version("any2api-automation")` 仍为 0.1.0。应用 Dockerfile 复用固定 browser-runtime，只 COPY 新源码，发行包元数据沿用旧基座。0.24.4 两种应用镜像用 `uv pip install --no-deps --reinstall-package` 安装当前项目 wheel，保留浏览器基座和运行依赖；`check_runtime_version.py` 在镜像构建中验证 project / installed distribution / API 一致，否则构建失败。两种镜像 CI 均成功；最终运行态核验单独记录。

发布期间 Automation 换 Pod，Backend 曾出现一次 DeepSeek profile refresh `No route to host`。新 Pod Ready 后继续核验；该启动期间日志不能作为厂商稳定性验收。

## 详细 changelog

| 分类 | 关键文件 / 模块 | 行为变化 | 兼容性与回滚 |
|---|---|---|---|
| OpenAI agent 协议 | `CanonicalRequestParser`、`OpenAiToolBridge`、`OpenAiResponseWriter` | namespace/custom text 桥接、历史/媒体/工具结果回放、稳定的流事件与 ID、completed/incomplete/failed 区分、开流前 HTTP 错误 | 扩展 Chat/Responses；strict/grammar/deferred 等未支持能力明确拒绝，完整变更见 0.24.0 验收记录 |
| 状态、权限与缓存 | `ResponsesService`、`GatewayResponseStore`、`ResponsesResourceController`、`ProviderResponseStateStore`、`PromptExactCacheManager` | store/续接/资源分页删除、Key 归属与当前权限、TTL/配额/大小、删除竞态保护、缓存按 Key/协议隔离 | 032 additive schema；store 缺省 false；旧无归属原生状态不向普通 Key 暴露；回滚应用时可保留新表 |
| 真实工具修复 | Python `mimo_browser.py`、`longcat_browser.py` | 字符串集合判断先检查类型，named/namespace/custom 对象 tool_choice 不再触发 `unhashable type: 'dict'` | 保留既有 string 和 dict 分支语义；新增两个 Provider × 两种 dict 形态的 4 项测试 |
| Key 列表读取 | `ApiKeyGrantStore.readAll`、`ApiKeyService.list` | 一次 root 查询，按最多 500 个 Key 批量查询四种 grant，避免逐 Key 查询 | API 字段、排序、权限与过期/通道策略保持；无权限缓存放宽；未知持久化权限仍 fail closed |
| 厂商列表读取 | `ProviderRuntimeService.list` | 八个逐厂商 SQL 改成一次批量状态/计数查询 | 保留 plugin 顺序、缺失 provider row 的 fallback 和 transport mode；单项读写逻辑沿用 |
| Runtime rules 读取 | `ProviderRuntimeRuleService.list` | states 一次、revision 一次，latest 50 加 active/candidate 引用批量加载 | 保留每厂商最近 50 条历史；更早的 active/candidate 仍可解析；不修改状态机或规则写入 |
| HTTP 线程隔离 | 新增 `WebFluxConfiguration` | 同步 Controller 经 WebFlux blocking executor 使用已有 `databaseExecutor` 虚拟线程 | 无新增线程池；reactive Controller 沿用显式调度；真实单 Netty worker 测试证明慢同步路由不阻塞 reactive 路由 |
| JSON 传输 | `application.yml` | 1024 bytes 起压缩 JSON/problem JSON/text；约 1.44MB 的模型目录传输约 109KB | `text/event-stream` 不在压缩 MIME 中，保留 SSE 增量路径；关闭 compression 可独立回退 |
| Chat SDK 回放 | `CanonicalRequestParser.chatMessages`、`CanonicalRequestParserTest` | 0.24.3 将有 tool_calls 的 assistant 缺省/null content 规范为无文本，解决 MiMo 工具结果回传 TypeError | 不修改 rawRequest、call_id、phase 或现有文本/媒体；不放宽其他 role/null content；补充 3 项回归 |
| 产物包装 | 两种 Automation Dockerfile、`check_runtime_version.py` | 0.24.4 安装当前项目包并核验 project/distribution/API 版本 | 不更新固定 browser-runtime，不重装依赖；source/lock/JAR/镜像 cohort 均使用新版本 |
| 发布门禁 | `.github/workflows/build-and-deploy.yml`、版本配置、`check_versions.py` | 四应用统一 SemVer，日期/版本/用途/SHA 镜像 tag；CI 版本门禁；纯 docs/tasks push 不触发重新发布 | 0.24.1 是协议新增能力后的部署候选；0.24.2 为已证实缺陷修复；不复用已验收候选 |
| API 验收 | `openai_agent_smoke.py`、SDK helpers；Codex 历史实验保留 | Grok 可省略 unsupported reasoning；真实第二 Key 验证 owner 404；`--timeout` 默认 30s、可显式 120s 并写入报告 | 测试使用限模型/协议/feature、2h 到期 Key，最终删除；标准 API/SDK 为核心验收，Codex 为可选证据 |
| 范围与文档 | 两份 requirements、`OPENAI_AGENTS.md`、报告、文档索引与任务板 | 固化 Chat/Responses 常用接口到厂商 WEB 的后端边界；当前 0.24.4 与 0.24.0 历史证据分开，扩展/客户端事项不作为核心门禁 | 仅 docs/tasks 变更；既有扩展、API 与通道保留。43 个相对链接、示例 Python 语法、diff check 和 source/JAR 版本复核通过，不重建镜像 |

## 本地与 CI 验证

| 验证 | 本轮结果 | 范围 |
|---|---|---|
| Backend `test bootJar` | 最终 384 项，379 passed / 5 skipped / 0 failures/errors；0.24.2 为 381 项 | 包含真实 PostgreSQL、Key 批量隔离/501 分批、Provider 顺序与计数、65 条 rule revisions、Netty 线程隔离、3 项 Chat null/content 回放 |
| Automation pytest | 486 passed | 全量现有测试 + 4 项对象 tool_choice 回归 |
| Automation ruff / uv lock | 全量 format/check 通过；offline lock check 通过 | 最终 123 个文件；未引入运行依赖 |
| Web | lint / build 通过 | 本轮管理 UI 仅版本变动 |
| 版本门禁 | source/lock/FastAPI/JAR version 0.24.4，PASS | 最新迁移 tag 0.24.0；新增实际 installed distribution / API 镜像构建门禁；本地 wheel 重装校验 PASS |
| Git diff | `git diff --check` 通过 | 最小功能范围修改，无用户改动覆盖 |
| GitHub Actions | 四项 metadata/quality、四镜像、update-gitops 均 success | browser-runtime 固定基座不在本轮重新构建 |

5 项 backend skipped 是 `GrokWebStatsigLiveInteropTest` 的 live 条件缺失；不算真实 Grok 验收通过。

## Read 性能证据

### 方法与限制

- 每个 endpoint 3 次顺序采样；外网 direct/public 两个客户端并行各自采样，属于低并发观察，**不是压测，也不是稳定的生产 p95**。n=3 的 p95 是该组最大值。
- 集群内从 Automation Pod 访问 `http://any2api-server:8080`，避免 WAN；鉴权使用现有管理登录或配置公共 Key，凭据只保留在进程内。
- 0.23.4 / 0.24.1 / 0.24.2 / 0.24.4 分开记录。0.24.2 集群首轮包含新 Pod 预热，未改善的端点不写成普遍提速。0.24.3/0.24.4 未再改变 Read 实现。
- decoded bytes 是应用 JSON 大小，wire bytes 是 HTTP 客户端实际接收的编码后 body，不含 TLS/header。服务端 gzip 已由响应头核实。
- 外网入口：`https://any2api-direct.mnnu.eu.org`、`https://any2api.mnnu.eu.org`。旧基线误测 `/v1/providers` 的 404 排除，实际公共目录为 `/api/catalog/v1/providers`。

### 集群内全量端点

下表每组 n=3，0.24.2 和最终 0.24.4 各 18 个端点 × 3 次全部 HTTP 200。0.24.4 采样与 MiMo SDK 验收同期，Key 列表包含两个临时测试 Key；全部已清理。

| Endpoint | 0.23.4 p50 ms | 0.24.1 p50 ms | 0.24.2 p50 / p95 ms | 0.24.4 p50 / p95 ms | Decoded / wire bytes (0.24.4) |
|---|---:|---:|---:|---:|---:|
| `/healthz` | 3.24 | 10.73 | 6.91 / 20.13 | 5.38 / 7.72 | 15 / 15 |
| `/readyz` | 81.07 | 159.84 | 85.69 / 167.30 | 83.28 / 162.09 | 15 / 15 |
| `/api/admin/v1/session` | 8.34 | 10.24 | 6.99 / 8.13 | 5.61 / 6.85 | 41 / 41 |
| `/api/admin/v1/overview` | 236.86 | 265.14 | 247.73 / 705.86 | 241.27 / 284.79 | 4487 / 989 |
| `/api/admin/v1/providers` | 723.45 | 727.65 | 178.09 / 194.82 | 165.50 / 170.62 | 5162 / 966 |
| `/api/admin/v1/accounts/page?page=0&size=25` | 241.56 | 290.57 | 464.40 / 499.47 | 247.28 / 283.87 | 8042 / 1959 |
| `/api/admin/v1/registration-jobs/page?page=0&size=25` | 239.40 | 252.97 | 266.56 / 294.44 | 241.17 / 251.94 | 23449 / 4852 |
| `/api/admin/v1/registration-schedules/page?page=0&size=25` | 235.96 | 252.47 | 268.16 / 277.19 | 240.43 / 263.67 | 6006 / 1189 |
| `/api/admin/v1/requests?page=0&size=25` | 244.96 | 248.72 | 274.47 / 285.55 | 250.35 / 255.19 | 12635 / 2685 |
| `/api/admin/v1/operations?page=0&size=25` | 246.81 | 246.69 | 280.28 / 333.71 | 259.23 / 331.25 | 12569 / 2875 |
| `/api/admin/v1/api-keys` | 2707.75 | 2711.77 | 301.34 / 371.43 | 244.87 / 277.77 | 15278 / 2837 |
| `/api/admin/v1/proxy-pools` | 401.03 | 318.02 | 328.54 / 332.31 | 318.47 / 319.05 | 832 / 832 |
| `/api/admin/v1/settings` | 310.77 | 315.47 | 326.46 / 347.64 | 324.32 / 332.27 | 562 / 562 |
| `/api/admin/v1/provider-runtime-rules` | 1393.87 | 1412.33 | 278.30 / 296.90 | 272.42 / 283.14 | 11805 / 2026 |
| `/api/admin/v1/models/limits` | 13.66 | 77.53 | 134.94 / 626.27 | 72.12 / 85.97 | 122530 / 4380 |
| `/api/catalog/v1/providers` | — | 11.11 | 13.21 / 110.34 | 26.41 / 37.43 | 7764 / 1386 |
| `/mimo/v1/models` | 10.51 | 66.69 | 69.53 / 74.61 | 52.47 / 69.77 | 81481 / 5262 |
| `/v1/models` | 20.79 | 91.30 | 115.60 / 181.69 | 94.28 / 128.95 | 1435718 / 108953 |

最终 0.24.4 相对 0.24.1：Key 列表 p50 下降约 **91.0%**，Runtime rules 约 **80.7%**，厂商列表约 **77.3%**。模型目录 wire body 约为 decoded body 的 7.6%，减少约 **92.4%**；这是字节改善，不直接等于延迟同比改善。

账号分页与 model limits 首轮比旧数据高，针对这个具体疑点补了预热后各 5 次观察：账号分页 **245.44 / 326.28ms**，model limits **21.01 / 57.97ms**，全部 200。其稳定读取未呈现首轮 464/135ms 的增幅，仍需生产 histogram 观察长尾。

### 外网端点

| Endpoint | 0.23.4 direct p50 ms | 0.24.2 direct p50 / p95 ms | 0.24.2 public p50 / p95 ms |
|---|---:|---:|---:|
| healthz | 473.83 | 465.08 / 815.08 | 471.50 / 473.29 |
| providers | 1511.19 | 598.98 / 698.19 | 608.28 / 618.92 |
| accounts page | 1205.44 | 1031.84 / 1344.49 | 715.59 / 1011.46 |
| api-keys | 3745.63 | 834.75 / 867.58 | 886.11 / 1270.42 |
| runtime rules | 9335.97，基线抖动较大 | 835.44 / 1185.66 | 777.90 / 1517.55 |
| all models | 4262.47 | 1922.38 / 2114.29 | 1495.23 / 1570.39 |

public 旧 api-keys p50 3757.11ms，runtime rules 2184.38ms，all models 1563.91ms。模型目录虽然压缩显著，public p50 从约 1.56s 到 1.50s，当前样本不能声称整体目录时延大幅下降。其他分页接口通常仍有约 0.7–1.1s，operations 本轮外网 p50 1.49–1.69s，有明显抖动。

以上 0.24.2 direct/public 各 18 × 3 次全部 200。当时服务端 http.server.requests aggregate max 约 624ms，而客户端部分观测更高；需继续分开观察服务器、边缘、连接与传输成本。

最终 **0.24.4 direct** 复验，每个端点 n=3、全部 HTTP 200：

| Endpoint | p50 / p95 ms |
|---|---:|
| `/healthz` | 532.56 / 924.16 |
| `/readyz` | 596.33 / 1072.79 |
| `/api/admin/v1/session` | 481.03 / 1592.46 |
| `/api/admin/v1/overview` | 1023.55 / 1146.82 |
| `/api/admin/v1/providers` | 675.90 / 1051.46 |
| `/api/admin/v1/accounts/page?page=0&size=25` | 681.37 / 729.99 |
| `/api/admin/v1/registration-jobs/page?page=0&size=25` | 705.37 / 728.44 |
| `/api/admin/v1/registration-schedules/page?page=0&size=25` | 798.87 / 1127.96 |
| `/api/admin/v1/requests?page=0&size=25` | 684.18 / 699.37 |
| `/api/admin/v1/operations?page=0&size=25` | 841.46 / 1105.30 |
| `/api/admin/v1/api-keys` | 776.95 / 1059.83 |
| `/api/admin/v1/proxy-pools` | 801.24 / 929.49 |
| `/api/admin/v1/settings` | 765.94 / 1513.90 |
| `/api/admin/v1/provider-runtime-rules` | 1134.79 / 1382.01 |
| `/api/admin/v1/models/limits` | 465.74 / 1354.60 |
| `/api/catalog/v1/providers` | 446.34 / 768.57 |
| `/mimo/v1/models` | 469.82 / 1042.11 |
| `/v1/models` | 1063.99 / 1089.56 |

最终模型目录 decoded/wire **1,435,743 / 108,976 bytes**，gzip。Key 列表从旧 direct 3.75s 到 0.78s；多数 DB Read 仍约 0.68–1.13s。0.24.2/0.24.4 的规则列表差异存在外网抖动，不能从 n=3 断言回退；两版 Read 代码相同且最终集群内约 272ms。

该轮 Hikari pending=0、active=1；2,186 次 acquire 累计 118.838s，平均 **54.36ms**、max 92.3ms，CPU usage 约 0.038。`http.server.requests` aggregate max 20.247s 包含同期推理，不作为 Read 服务端最大耗时。最终未重复 public 全量采样；public 协议错误透传另列待验。

### 已证实的原因与本轮处理

1. **数据库往返被 N+1 放大。** 33 个 Key 原为 root 1 次 + grants 33 次查询，改为 1 + 1（超过 500 才分批）。四张 grant 表原来已 UNION，不误计为四次/Key。Runtime rules 原为 1 次 ID + 8 × 2 次 state/revisions，改为 2 次；更早的 active/candidate 同时加载。Provider 状态原为 8 次，改为 1 次。以上 SQL 数不含事务控制往返。
2. **同步 Controller 缺 WebFlux blocking executor。** 项目已有 `ExecutorService` 虚拟线程 bean，但它并非 Boot 自动配置所需的 applicationTaskExecutor，配置也未开启相应自动路径。同步 JDBC/JPA handler 会占用 Netty；本轮显式配置已有 executor。reactive overview、catalog/auth loader 本来有 subscribeOn，保持原路径。[Spring WebFlux 配置说明](https://docs.spring.io/spring-framework/reference/web/webflux/config.html)说明同步 handler 的 blocking execution 配置与默认 predicate。
3. **应用和 PostgreSQL 跨节点，单次网络成本显著。** 集群内 readiness SQL 约 80ms；从 Automation 做 5 次 TCP connect，PostgreSQL median 152.6ms，server/Redis 约 77ms。TCP handshake 不等于精确 SQL RTT，但与跨节点部署、多次查询累计耗时相互印证。仍需测具体 CNI/overlay/节点链路，不能仅从“不同节点”推断网络必然应如此慢。
4. **公网网络约 470–530ms 基础成本。** 15 bytes health 响应各轮 p50 约 465–533ms，集群内约 3–11ms。约 250–330ms 的 DB Read 加到客户端网络后，0.7–1.1s 体感可以复现；单次外网抖动可更高。
5. **模型目录同时承担静态能力与动态状态，body 大。** 完整 capabilities、metadata、token limits、runtime 等多个视图重复序列化；319 个 enabled 模型本轮约 1.44MB。gzip 处理传输量，后续还应减少重复投影/读取，不删用户需要的管理信息。
6. **冷目录 SQL 仍有优化空间。** 对 `ModelCatalogCache.MODEL_QUERY` 在当前 PG18.4 执行只读 EXPLAIN ANALYZE，319 rows，Execution 243.454ms、Planning 8.044ms、JIT 94.993ms、shared hits 84,992；账号 LATERAL 部分按模型重复执行 319 次。暖目录旧集群 p50 20.79ms，说明不能把所有 Read 都归结为同一慢 SQL。

采样时 Hikari pending=0、active=0–1；462 次 acquire 累计 21.597s，平均约 46.7ms，max 102ms。虽然没有排队，借连接的校验/网络等成本仍应分段观察。外网复测后 server CPU 约 47m、memory 514Mi，PostgreSQL 12m/197Mi。没有证据支持先扩大连接池或添加随机索引。现有 usage/operation/account 数据规模仍适合直接投影和批量查询，不需要引入额外读库体系。

## 标准 OpenAI API 与厂商 WEB 验收

核心验收使用 OpenAI Python **2.54.0**，真实厂商 WEB、无客户端自动重试、AUTO 通道、精确模型 scope；30s 超时失败与显式 120s 验证分别保留。Codex CLI **0.159.2** 为可选实验。SDK 完整 7 组包含 namespace/custom 扩展，报告按普通接口与扩展逐项判断。

| 版本 / 厂商模型 | 实际结果 | 结论边界 |
|---|---|---|
| 0.24.1 MiMo / `mimo-v2-flash` | 文本可以返回，但 required tool 返回 `tool_call_generation_failed`；Codex 只得到“服务器繁忙，请稍后再试” | 历史目录/旧 READY 不能作为当前工具能力保证 |
| 0.24.1 MiMo / `mimo-v2.6-flash`、LongCat / `longcat-flash` | 文本/SSE、stored function loop 成功；named namespace 触发 Runtime `unhashable type: 'dict'` | 已由 0.24.2 修复并补测试 |
| 0.24.2 MiMo / `mimo-v2.6-flash`，30s | 前四组通过；custom 结果回传客户端 timeout，服务记录 downstream_cancelled | 厂商首帧延迟未达到 30s；不是 HTTP Read 列表耗时 |
| 0.24.2 MiMo / `mimo-v2.6-flash`，120s | Responses 全部六组通过；Chat assistant `content:null` 回传失败，API/Runtime translator TypeError | 已由 0.24.3 在 canonical 边界修复；最终复验另记 |
| 0.24.4 MiMo / `mimo-v2.6-flash`，120s | **7 组全部通过**；first delta 9.159s，stream total 10.054s | null-content 修复真实验证；Responses 与 Chat 文本工具闭环通过，不包含真实并行压力/长对话；图片另验 |
| 0.24.2 LongCat / `longcat-flash`，120s | **7 组全部通过**；first delta 5.615s，stream total 7.039s | Responses/SDK SSE、stored function loop/分页/删除/跨 Key 404、namespace、自定义工具回放及 SSE、Chat 工具循环/usage |
| 0.24.4 LongCat / `longcat-flash`，120s | **7 组全部通过**；first delta 5.963s，stream total 6.672s | 最终镜像上的 Chat/Responses、工具回传与权限复验通过；图片限制另记 |
| 0.24.1 Grok Web / `grok-3` | reasoning 请求 400；省略 reasoning 后 503 model_unavailable，probe FAILED | 正确能力拒绝与当时运行失败分别记录；账号 ACTIVE 不证明推理可用 |
| 0.24.3 Grok Web / `grok-3` | 手动文本 probe READY，27.767s，UTC 14:20:29.986 | 比之前 FAILED 更新，但尚不等于工具验收；最终 SDK 另记 |
| 0.24.4 Grok Web / `grok-3`，120s、无 reasoning | **前三组通过**：Responses 非流式/SSE/stored function loop、资源分页删除和跨 Key 404；namespace 指定工具流失败，502 `empty_model_response` | request_id `a87531fa-20d8-4a55-be24-ec643122d6f8`；三账号尝试均内部 HTTP 200、约 13/12/15s 无有效模型输出。普通 function 已通过，namespace 属可选扩展；custom/Chat 后续组未执行，不写为失败或通过 |
| 0.24.4 Grok Web / 单独常用 Chat 验收，120s | **首个 required function 请求客户端超时**，`APITimeoutError`；结果回传与 Chat SSE 未执行 | request_id `31ef2e9b-6b3d-4665-9c89-56dfec30cacc`，实际 channel `camoufox_browser_runtime`；后端记录 120,561ms、`downstream_cancelled`、account acquire 643ms、尚无首帧。无 namespace/custom/reasoning；常用 Chat 尚未通过，WEB 无首帧的根因未定位，不将客户端取消归类为已收到的上游 HTTP 错误 |
| 0.24.4 MiMo / 标准 Responses 工具图片回放 | **PASS**，completed，20.646s，正确识别图片为橙色 | 官方 SDK 将图片作为 `function_call_output.output` 的 input_image 回放，保留 call_id；预期颜色未写入 prompt。证明此形态可桥接，未覆盖任意文件/音视频 |

### 后端 WEB 桥接的实际缺口

1. **LongCat 工具图片结果适配与错误分类。** 实际 OpenAI payload 中，缺 FILE_UPLOADS 的 Key 被 403 正确拒绝；补齐 scope 后适配器返回 **502 `LongCat media must be attached to the last user message`**，随后 **503 circuit_open**。parser 保留了工具媒体，但 upload strategy 仅支持末尾 user 媒体；应支持可行形态或在执行前明确 400，输入/adapter 校验错误不得影响整个模型熔断。不为迁就 WEB 上传接口改变 call_id/role 或丢弃图片。
2. **工具能力与文本 READY 的区分。** MiMo 旧模型忙提示、Grok 空输出说明普通文本探活不能代表 required function 可靠性。按实际常用模型补文本、function 生成/回传、图片的能力证据，不要求每个厂商实现全部工具扩展。
   Grok 独立 Chat required function 在 120s 客户端超时，这是常用接口的真实待修项；之前 Responses function 成功不替代 Chat 验收，后续 Chat 回传/SSE 尚未执行。
3. **Grok namespace（扩展限制）。** 普通 Responses function 循环已经通过；带 namespace 的指定工具返回空输出，具体上游选择/帧解析/别名原因尚未定位。保留错误证据，按实际使用需求再处理，不将扩展修复列为常用接口交付硬门槛。
4. **公网错误透传。** 本轮真实 SDK 使用 direct 入口；Cloudflare public 入口曾将后端 502 OpenAI error 包装为通用 JSON。Read 的 HTTP 200 不证明错误兼容；后续核验错误 code/type/request_id 与 SSE 终态透传。
5. **未验证范围。** 其他厂商的这些常用协议组合、真实并行压力、长对话和持续稳定性尚未完成本轮逐项验收。未验不等于不支持；继承 READY、声明 capability 或本地 fixture 不替代真实请求。

### 可选客户端实验（不作为后端缺口）

LongCat shell smoke 被本机 PowerShell policy 拒绝，未绕过；最终曾出现 tool marker 被当作文本输出的现象，只有在标准 function API 可复现时才归入 WEB 桥接缺陷。Codex 自定义路由出现 model metadata fallback，是客户端配置事项。本项目不规划专属 model catalog 或调整本机 shell/patch 权限。

0.24.4 MiMo 真实本地图片实验 exit 0，最终回答正确为橙色；CLI JSON 未出现显式 image_view completion item，因此不将标记当成完整工具执行轨迹。标准 Responses 工具图片结果已按上表独立验证。0.24.0 的 fixture 图片闭环保留历史证据。

所有临时测试 Key 删除均返回 204；核验恢复原 **33 个 Key，测试名前缀残留 0**。没有保存或输出凭据，测试 helper/报告位于 ignored `backend/build/`。

## 下一步与回滚

### 按优先级推进

| 优先级 | 工作 | 验收与取舍 |
|---|---|---|
| P0 | 常用 Chat/Responses 的 WEB 适配与错误分层 | 优先定位 Grok Chat required function 超时，处理 LongCat 工具图片和错误熔断；function 定义/参数/call_id/结果完整回放，无法支持的图片形态在执行前 400。常用 tool_choice、取消与上游失败保持正确终态，不扩展到完整厂商官方 API |
| P1 | 实际使用厂商的核心验收 | 按需验证文本、多轮、SSE、普通 function、工具结果、图片和权限；工具 marker 混入文本等现象通过标准 API 复现后再修。namespace/custom 不是各厂商的强制门禁 |
| P1 | 工具能力探针与可靠性 | 模型/通道分开记录普通文本、required function、结果回传、图片；MiMo 旧模型忙提示和 Grok READY 波动进入能力/错误分类；记录 TTFT/p95、取消和上游超时。探针按需/低频，避免全量周期调用 WEB 消耗账号额度 |
| P1 | 公网 OpenAI 错误透传 | direct/public 对比非法输入、权限、上游失败和开流后错误；保留 OpenAI code/type/request_id，不被边缘统一替换 |
| P1 | 降低网络与多次读往返 | 先测 DNS/CNI/overlay/节点链路，再评估 server 与 PostgreSQL 的放置；减少 count+page、纯读事务和借连接校验的额外 RTT，保留需要的一致性/LOB 边界。不直接搬迁 PV |
| P1 | 目录读取与冷 SQL | 在后端保留现有字段，复用按 Provider 读取、减少重复组装；静态能力与动态 runtime 分开缓存，评估条件请求；按 provider/model 批量预聚合，比较 JIT on/off 的实际计划。需要新增投影契约时先明确兼容边界 |
| P1 | Read 观测与预算 | URI histogram 与稳定 p95/p99，request_id 分段记录 auth/cache/acquire/SQL/transaction/serialization/network；按可感知页面组合测量，不用 n=3 充当容量验收 |
| 按需保留 | 可选扩展 | 已实现 namespace/custom/状态资源保留，不继续追求协议全集。WebSocket、background/Conversations、hosted tools、strict/grammar/deferred、加密 reasoning、语义 compaction 和新增音视频/Files 协议不在当前计划 |

回滚采用前一套不可变镜像。0.24.4 包装修复可退到 0.24.3（会重新出现旧 distribution metadata）；撤回 null-content 修复可退到 0.24.2；撤回 Read/对象 choice 修复可退到 0.24.1。需要整体撤回 agent 升级时使用上一 0.23.4 制品。032 additive schema 可以保留，生产 schema rollback/删除状态未执行。

| 0.24.1 回滚组件 | 已核验 digest |
|---|---|
| server | `sha256:38b61ba8c6dc959de9edbc317e9e5fafb5ec1e0762e26818dec5e6bd256ba56c` |
| automation | `sha256:8e7e2423eba76657bd662d3abb1e9e7863df1e4417efe0b0c13603abbbb567d8` |
| automation-arena | `sha256:cca3e62404eabb43d2d6b56597dc0e4121dba9cc4e72e1ec30b395da0f3a2e4f` |
| web | `sha256:dc86977584cabd3d33781537c0fe6bcef6701796db607c2b4ed6ad2762a21886` |

原始测量、无凭据的运行快照和 smoke 日志在本地 ignored `backend/build/`；本报告保存结论、关键数值、代码/CI/GitOps/Pod 证据。最终范围/验收/任务板文档变更单独提交，不重建已验收 0.24.4 制品，不改变生产 API/配置或版本。
