# OpenAI agent 发布、真实验收与 Read 性能（2026-10-02）

## 范围与结论口径

用户已授权提交、部署和测试。本轮沿用 GitHub Actions → `ircs-prod-config` → Argo CD 的生产发布链路，先发布协议升级 0.24.1，再将真实验收发现的工具解析和 Read 性能问题修复为 0.24.2。所有运行态证据只对应本报告日期；健康、文本推理、工具闭环和实际 Codex 使用分别判断。

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

镜像仓库：`docker.io/speedproxy/any2api`。每个镜像 tag 为 `<component>-20261002-v<version>-release-<full-source-sha>`，未覆盖旧 tag。032 的 tag 保留新增结构所属的业务版本 0.24.0。

### 0.24.2 实际 Pod 与 imageID

| 组件 / Pod | imageID digest |
|---|---|
| server / `any2api-server-6f4bf9b689-rj5md` | `sha256:abce395c4a0bd77505350da10d20c532c632731ec715f00976c12f9cce6bfdd7` |
| automation / `any2api-automation-5bcdb7bc5f-8jh46` | `sha256:a2d791c4a87bb9e41a759808c197a787f1fa0b99f7373a607748b9934d2fee9a` |
| automation-arena / `any2api-automation-arena-774b67bb78-ts9sh` | `sha256:e3efeedc5152d303105dba7d940d0cbd8b1ef3a996b051dc6112e54b7ce9e08d` |
| web / `any2api-web-6459d7b68f-6kdbr` | `sha256:dddf91902e0439943eeb33f5fbb47e74b098db1342d1493a8139bd9ba880a1fc` |

应用位于 `instance-20250708-1530`，PostgreSQL 位于 `instance-20251229-0833`；Redis 与应用同节点。本轮没有迁移数据库或改变 Pod 放置策略。

**包装缺口**：Automation HTTP OpenAPI version 为 0.24.2，但 `importlib.metadata.version("any2api-automation")` 仍为 0.1.0。应用 Dockerfile 复用固定 browser-runtime，只 COPY 新源码，发行包元数据沿用旧基座。源码/HTTP 版本一致，Python distribution/SBOM 版本未完成统一，应补 packaging 及产物门禁。

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
| 发布门禁 | `.github/workflows/build-and-deploy.yml`、版本配置、`check_versions.py` | 四应用统一 SemVer，日期/版本/用途/SHA 镜像 tag；CI 版本门禁；纯 docs/tasks push 不触发重新发布 | 0.24.1 是协议新增能力后的部署候选；0.24.2 为已证实缺陷修复；不复用已验收候选 |
| 客户端验收 | `openai_agent_smoke.py`、SDK/Codex helpers | Grok 可省略 unsupported reasoning；真实第二 Key 验证 owner 404 | 测试使用限模型/协议/feature、2h 到期 Key，最终删除；不修改全局 Codex 配置或绕过执行策略 |

## 本地与 CI 验证

| 验证 | 0.24.2 结果 | 范围 |
|---|---|---|
| Backend `test bootJar` | 381 项，376 passed / 5 skipped / 0 failures/errors | 包含真实 PostgreSQL、Key 批量隔离/501 分批、Provider 顺序与计数、65 条 rule revisions、Netty 线程隔离 |
| Automation pytest | 486 passed | 全量现有测试 + 4 项对象 tool_choice 回归 |
| Automation ruff / uv lock | 全量 format/check 通过；offline lock check 通过 | 122 个文件已格式化，未引入依赖 |
| Web | lint / build 通过 | 本轮管理 UI 仅版本变动 |
| 版本门禁 | source/lock/FastAPI/JAR version 0.24.2，PASS | 最新迁移 tag 0.24.0；Python 安装发行包的旧元数据不在当前门禁覆盖内 |
| Git diff | `git diff --check` 通过 | 最小功能范围修改，无用户改动覆盖 |
| GitHub Actions | 四项 metadata/quality、四镜像、update-gitops 均 success | browser-runtime 固定基座不在本轮重新构建 |

5 项 backend skipped 是 `GrokWebStatsigLiveInteropTest` 的 live 条件缺失；不算真实 Grok 验收通过。

## Read 性能证据

### 方法与限制

- 每个 endpoint 3 次顺序采样；外网 direct/public 两个客户端并行各自采样，属于低并发观察，**不是压测，也不是稳定的生产 p95**。n=3 的 p95 是该组最大值。
- 集群内从 Automation Pod 访问 `http://any2api-server:8080`，避免 WAN；鉴权使用现有管理登录或配置公共 Key，凭据只保留在进程内。
- 0.23.4 / 0.24.1 / 0.24.2 分开记录。0.24.2 集群首轮包含新 Pod 预热，未改善的端点不写成普遍提速。
- decoded bytes 是应用 JSON 大小，wire bytes 是 HTTP 客户端实际接收的编码后 body，不含 TLS/header。服务端 gzip 已由响应头核实。
- 外网入口：`https://any2api-direct.mnnu.eu.org`、`https://any2api.mnnu.eu.org`。旧基线误测 `/v1/providers` 的 404 排除，实际公共目录为 `/api/catalog/v1/providers`。

### 集群内全量端点

下表每组 n=3，0.24.2 的 18 个端点共 54 次全部 HTTP 200。

| Endpoint | 0.23.4 p50 ms | 0.24.1 p50 ms | 0.24.2 p50 / p95 ms | Decoded / wire bytes |
|---|---:|---:|---:|---:|
| `/healthz` | 3.24 | 10.73 | 6.91 / 20.13 | 15 / 15 |
| `/readyz` | 81.07 | 159.84 | 85.69 / 167.30 | 15 / 15 |
| `/api/admin/v1/session` | 8.34 | 10.24 | 6.99 / 8.13 | 41 / 41 |
| `/api/admin/v1/overview` | 236.86 | 265.14 | 247.73 / 705.86 | 4487 / 982 |
| `/api/admin/v1/providers` | 723.45 | 727.65 | 178.09 / 194.82 | 5162 / 965 |
| `/api/admin/v1/accounts/page?page=0&size=25` | 241.56 | 290.57 | 464.40 / 499.47 | 8042 / 1953 |
| `/api/admin/v1/registration-jobs/page?page=0&size=25` | 239.40 | 252.97 | 266.56 / 294.44 | 23449 / 4852 |
| `/api/admin/v1/registration-schedules/page?page=0&size=25` | 235.96 | 252.47 | 268.16 / 277.19 | 6006 / 1189 |
| `/api/admin/v1/requests?page=0&size=25` | 244.96 | 248.72 | 274.47 / 285.55 | 12581 / 2679 |
| `/api/admin/v1/operations?page=0&size=25` | 246.81 | 246.69 | 280.28 / 333.71 | 12739 / 2996 |
| `/api/admin/v1/api-keys` | 2707.75 | 2711.77 | 301.34 / 371.43 | 14332 / 2650 |
| `/api/admin/v1/proxy-pools` | 401.03 | 318.02 | 328.54 / 332.31 | 832 / 832 |
| `/api/admin/v1/settings` | 310.77 | 315.47 | 326.46 / 347.64 | 562 / 562 |
| `/api/admin/v1/provider-runtime-rules` | 1393.87 | 1412.33 | 278.30 / 296.90 | 11805 / 2027 |
| `/api/admin/v1/models/limits` | 13.66 | 77.53 | 134.94 / 626.27 | 122530 / 4380 |
| `/api/catalog/v1/providers` | — | 11.11 | 13.21 / 110.34 | 7764 / 1382 |
| `/mimo/v1/models` | 10.51 | 66.69 | 69.53 / 74.61 | 81480 / 5255 |
| `/v1/models` | 20.79 | 91.30 | 115.60 / 181.69 | 1435588 / 108962 |

Key 列表相对 0.24.1 p50 下降约 **88.9%**，Runtime rules 约 **80.3%**，厂商列表约 **75.5%**。模型目录 wire body 约为 decoded body 的 7.6%，减少约 **92.4%**；这是字节改善，不直接等于延迟同比改善。

账号分页与 model limits 首轮比旧数据高，针对这个具体疑点补了预热后各 5 次观察：账号分页 **245.44 / 326.28ms**，model limits **21.01 / 57.97ms**，全部 200。其稳定读取未呈现首轮 464/135ms 的增幅，仍需生产 histogram 观察长尾。

### 外网端点

| Endpoint | 0.23.4 direct p50 ms | 0.24.2 direct p50 / p95 ms | 0.24.2 public p50 / p95 ms |
|---|---:|---:|---:|
| healthz | 473.83 | 465.08 / 815.08 | 471.50 / 473.29 |
| providers | 记录见原始基线 | 598.98 / 698.19 | 608.28 / 618.92 |
| accounts page | 记录见原始基线 | 1031.84 / 1344.49 | 715.59 / 1011.46 |
| api-keys | 3745.63 | 834.75 / 867.58 | 886.11 / 1270.42 |
| runtime rules | 9335.97，基线抖动较大 | 835.44 / 1185.66 | 777.90 / 1517.55 |
| all models | 4262.47 | 1922.38 / 2114.29 | 1495.23 / 1570.39 |

public 旧 api-keys p50 3757.11ms，runtime rules 2184.38ms，all models 1563.91ms。模型目录虽然压缩显著，public p50 从约 1.56s 到 1.50s，当前样本不能声称整体目录时延大幅下降。其他分页接口通常仍有约 0.7–1.1s，operations 本轮外网 p50 1.49–1.69s，有明显抖动。

direct/public 各 18 × 3 次全部 200。服务端 http.server.requests aggregate max 约 624ms，而客户端部分观测更高；需继续分开观察服务器、边缘、连接与传输成本。

### 已证实的原因与本轮处理

1. **数据库往返被 N+1 放大。** 33 个 Key 原为 root 1 次 + grants 33 次查询，改为 1 + 1（超过 500 才分批）。四张 grant 表原来已 UNION，不误计为四次/Key。Runtime rules 原为 1 次 ID + 8 × 2 次 state/revisions，改为 2 次；更早的 active/candidate 同时加载。Provider 状态原为 8 次，改为 1 次。以上 SQL 数不含事务控制往返。
2. **同步 Controller 缺 WebFlux blocking executor。** 项目已有 `ExecutorService` 虚拟线程 bean，但它并非 Boot 自动配置所需的 applicationTaskExecutor，配置也未开启相应自动路径。同步 JDBC/JPA handler 会占用 Netty；本轮显式配置已有 executor。reactive overview、catalog/auth loader 本来有 subscribeOn，保持原路径。[Spring WebFlux 配置说明](https://docs.spring.io/spring-framework/reference/web/webflux/config.html)说明同步 handler 的 blocking execution 配置与默认 predicate。
3. **应用和 PostgreSQL 跨节点，单次网络成本显著。** 集群内 readiness SQL 约 80ms；从 Automation 做 5 次 TCP connect，PostgreSQL median 152.6ms，server/Redis 约 77ms。TCP handshake 不等于精确 SQL RTT，但与跨节点部署、多次查询累计耗时相互印证。仍需测具体 CNI/overlay/节点链路，不能仅从“不同节点”推断网络必然应如此慢。
4. **公网网络约 470ms 基础成本。** 15 bytes health 响应稳定约 465–474ms，集群内约 3–11ms。约 250–330ms 的 DB Read 加到客户端网络后，0.7–1.0s 体感可以复现。
5. **模型目录同时承担静态能力与动态状态，body 大。** 完整 capabilities、metadata、token limits、runtime 等多个视图重复序列化；319 个 enabled 模型本轮约 1.44MB。gzip 处理传输量，后续还应减少重复投影/读取，不删用户需要的管理信息。
6. **冷目录 SQL 仍有优化空间。** 对 `ModelCatalogCache.MODEL_QUERY` 在当前 PG18.4 执行只读 EXPLAIN ANALYZE，319 rows，Execution 243.454ms、Planning 8.044ms、JIT 94.993ms、shared hits 84,992；账号 LATERAL 部分按模型重复执行 319 次。暖目录旧集群 p50 20.79ms，说明不能把所有 Read 都归结为同一慢 SQL。

采样时 Hikari pending=0、active=0–1；外网复测后 server CPU 约 47m、memory 514Mi，PostgreSQL 12m/197Mi。没有证据支持先扩大连接池或添加随机索引。现有 usage/operation/account 数据规模仍适合直接投影和批量查询，不需要引入额外读库体系。

## 真实客户端与厂商验收

验收执行中；完成后在本节记录逐项结果、客户端版本、超时边界和剩余阻断。所有临时 Key 最终删除，失败结果保留，不将普通文本 200 判为工具就绪。

## 下一步与回滚

后续优先级随真实厂商测试补齐。本轮回滚补丁首选 0.24.1 的四个不可变镜像；需要整体撤回 agent 升级时使用上一 0.23.4 制品。032 additive schema 可以保留，生产 schema rollback/删除状态未执行。

| 0.24.1 回滚组件 | 已核验 digest |
|---|---|
| server | `sha256:38b61ba8c6dc959de9edbc317e9e5fafb5ec1e0762e26818dec5e6bd256ba56c` |
| automation | `sha256:8e7e2423eba76657bd662d3abb1e9e7863df1e4417efe0b0c13603abbbb567d8` |
| automation-arena | `sha256:cca3e62404eabb43d2d6b56597dc0e4121dba9cc4e72e1ec30b395da0f3a2e4f` |
| web | `sha256:dc86977584cabd3d33781537c0fe6bcef6701796db607c2b4ed6ad2762a21886` |

原始测量、无凭据的运行快照和 smoke 日志在本地 ignored `backend/build/`；本报告保存结论、关键数值、代码/CI/GitOps/Pod 证据。最终文档变更单独提交，不重建已验收 0.24.2 制品。
