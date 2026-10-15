# 1.4.0 发布修复与运行验收

## 发布状态

2026-10-15，时间统一为 UTC+8。用户先要求检查升级后的运行情况，随后明确授权“修复好之后发布 1.4”。本报告将质量门禁、镜像/部署、接口和真实厂商调用分别记录。

- 修复源码：`2e4a080ad58f0645991ca7e8efb9130179be2a69`，已推送 main。
- [正式发布 CI 38746417044](https://github.com/Prodigalgal/any2api/actions/runs/38746417044)：成功，质量门禁、六个镜像构建及 GitOps 更新全部通过。
- 生产已发布 **1.4.0**；GitOps `7a58b93e613949c892dc7438e49d42085280386b`，Argo `Synced/Healthy`、同步操作 `Succeeded`；五个应用实际版本均为 1.4.0，Ready、零重启，digest 已与仓库核对。
- 发布前生产：1.3.0，源码 `e7fa98e856a4f758d1eefff95e8e9a716337776a`，五个应用 Ready、零重启；Argo `Synced/Healthy`，revision `608003408af67378dfec211732ddf6b3483817fa`。Qwen 零副本。
- 发布规格及回滚：[RELEASE_1.4.0_OPERATIONS_HARDENING.md](../requirements/RELEASE_1.4.0_OPERATIONS_HARDENING.md)。

## Changelog

### 业务功能（沿用已有 1.4.0 源码）

- `MimoProvider`：内部多模态候选改为 MiMo 2.6 Flash/Pro。
- `RegistrationCaptchaPolicy`、注册任务/全局设置与 Web 表单：增加 `aiInternalModel`，任务可选具体内部验证码模型，全局设置可提供默认值。
- Python `CaptchaAiPolicy` / `SolverRegistry._visual_transports`：指定模型走 `/v1/chat/completions`；系统默认或显式 random 走原 `/multimodal-random/v1/chat/completions`；任务配置优先于进程默认。

### 发布与稳定性修复

- `automation/main.py`：FastAPI 版本 `1.3.0 -> 1.4.0`，修复原 CI 38676370769 的版本契约失败；`captcha/policy.py`、`captcha/registry.py` 通过 Ruff 格式门禁。
- `AutomationProviderCatalog`：单一构造器注入已有 `ProviderInstallationCatalog`，复用其启用状态缓存；公共目录地址继续刷新，停用厂商专用目录地址跳过，重新启用后恢复；地址去重保持。
- `deploy/gitops/automation-runtime-resources.yaml`：公共 automation CPU request `200m -> 500m`，limit `1 -> 2` 核；内存保持 3Gi，探针保持 3s，滚动策略保持原配置。
- 修复依据：公共 automation 原 1 核持续满载，采样十个 CPU 周期全部节流，1s 内累计节流约 0.865s；保留 21:06:26 的 readiness 超时事件（累计 13 次）。Qwen 已停用/零副本，后端仍每分钟访问其独立服务并告警。

### 兼容与数据边界

- `aiInternalModel` 是向后兼容的可选字段，旧任务保持默认随机路由；Java wire round trip 和旧 alias 已覆盖。公开 Chat/Responses 契约无变更。
- 无数据库迁移、账号启停、密钥轮换或代理池变更。七家 INFERENCE/LIFECYCLE/REGISTRATION 均绑定 Self-hosted Oracle 四节点。
- 保持入库后一次推理验证；本轮未新增厂商生成探活。发布前 571 个账号/571 条验证记录，缺失 0、RUNNING 0；过期调度租约 0。历史 FAILED 记录保留。

## 验证

### 本地与 CI

- Backend 全量 588 项：583 passed、5 conditional skipped，失败/错误 0；`test bootJar` 成功，源码与 JAR 均为 1.4.0。
- 新增回归：专用目录启停/共享地址去重，Java 验证码模型字段 wire 往返与旧请求兼容，任务模型/进程默认/显式 random 的路由优先级。
- Automation 572 passed；Ruff check、format check 通过。一个 FastAPI TestClient 上游弃用提醒，未影响测试。
- Web ESLint、TypeScript/Next.js production build 通过。
- CI 的 Backend/Automation/Web 质量门禁已通过。

### 生产验收

统一镜像后缀：`20261015-v1.4.0-release-2e4a080ad58f0645991ca7e8efb9130179be2a69`。镜像位于 `docker.io/speedproxy/any2api`；通过实际启动日志、Automation 安装包检查器和 Web package.json 分别验证运行版本，非仅核对 tag。

| 服务 | 当前 Pod | 仓库/运行 digest（一致） |
| --- | --- | --- |
| server | any2api-server-6545988cf-982kf | sha256:d74788709c349332928193d9a30fd6ddaa306467ccd6eee8d2334f7c09eaa029 |
| automation | any2api-automation-56445969fc-45plh | sha256:fcb10e83e413864bfda362a05717ca1070a22f48fd2d0e8d01fa22df58a13646 |
| automation-arena | any2api-automation-arena-86d979d65b-q8mvh | sha256:3a76afe4c21239be39e365ece877d854514e39f259993af52cb15534aad9411c |
| automation-deepseek | any2api-automation-deepseek-9b4f9ffc-rw9hl | sha256:9d37e3389befc741b62330a95895ac148c8ce308198b2387b60fe958301ed58b |
| web | any2api-web-c9cff548f-ll7p4 | sha256:d9ad1ae35e24e364b926cd1ba061ba66b143a9ba180802f1c00b976528dd27e6 |

- PostgreSQL、Redis Ready、零重启。Qwen 继续停用、零副本，旧停放镜像不属于此次运行验收。
- 稳态资源配额：limits CPU **8/10 核**、memory **13696Mi/20Gi**；requests CPU **1350m/3 核**、memory **4480Mi/8Gi**。
- 滚动过程保留了短暂的 `FailedCreate/exceeded quota` 事件：旧 Pod Terminating 时仍占额度，新副本创建暂时受阻，旧 Pod 退出后自动恢复；没有修改配额或删除事件。也保留了启动阶段端口尚未监听的探针事件，不能声称全过程零告警。
- 公共 automation 5.06s 样本：49 个 CPU 周期、节流 0，实际平均约 0.307 核；五次 `/health/ready` 均 200/ok，约 2.30–53.51ms。Arena 五次约 1.89–76.85ms，DeepSeek 约 2.21–134.31ms，均 200/ok。Arena 样本有 2/51 周期节流（累计 0.035s）；两组前后采样负载不同，不能据此量化长期性能收益。
- 两个公网入口 `https://any2api-direct.mnnu.eu.org/v1`、`https://any2api.mnnu.eu.org/v1`：health/ready 均 200；七家各自 Key 的 `/v1/models` 均 200，模型归属正确、无 `available` 预测字段；匿名 Chat/Responses 均返回 401 和 OpenAI JSON `request_id`。没有发出带有效 Key 的生成测试。
- `/api/admin/v1/settings` 返回新的 `registrationDefaults.aiInternalModel` 字段。六类只读管理接口均 200，下表为公网单连接两次采样，单位 ms：

| 接口 | 第一次 | 第二次 |
| --- | ---: | ---: |
| overview | 630 | 399 |
| accounts/page | 545 | 407 |
| registration-jobs/page | 526 | 424 |
| requests | 403 | 428 |
| settings | 393 | 395 |
| catalog/providers | 395 | 401 |

- 七家三类流量仍全部绑定 Self-hosted Oracle 四节点。验证器生成请求数为 0。
- 新 server Pod 日志采样：Qwen 目录连接告警 **0**；MiMo 11 个有完成尝试的 request_id 均出现成功，MiniMax 1/1，Grok 3/4 出现成功。Grok 仍记录 `provider_stream_error` 2 次和 `upstream_unavailable` 1 次，后续重试可能改变快照，不能声称所有厂商恢复。
- 时间依据：SQL 查询使用数据库时间；同轮核对 PostgreSQL、server、automation、Arena 容器时钟相互一致。本地采集客户端约落后服务器 128s，事件排序不混用客户端与服务端时间。

脱敏原始验收记录保存在本地忽略目录 `backend/build/`：`production-v140-baseline-20261015.json`、`production-v140-verified-20261015.json`、`production-v140-registry-digests-20261015.json`、`production-v140-stability-20261015.json`、`production-v140-traffic-20261015.json`。这些记录包含状态、版本、digest、耗时和计数，不保存密钥或用户提示正文。

## 厂商问题与性能边界

- Arena：发布前普通流量存在大量 `anti_bot_rejected`、`credential_rejected`，近两小时快照 12 个 request_id 没有成功记录；生命周期亦有长任务及租约超时。此次 CPU/目录修复不代表 Arena 反爬解除。
- Grok：发布前两小时快照 239 个 request_id，225 个有成功记录，336 次尝试；`provider_stream_error` 导致额外重试。不能将尝试失败率直接当成最终请求失败率。
- MiMo：同窗口 292 个 request_id、256 个有成功记录；36 个未成功中 35 个仅记录客户端取消。MiniMax 195 个、189 个成功，6 个未成功均为客户端取消。快照按请求开始时间过滤，后续重试完成可改变统计。
- DeepSeek：16 个账号均未启用，不能用其专用容器 Ready 或模型列表 200 声称推理恢复；最近注册仍报 `challenge_failed`。
- GLM/LongCat：模型列表和账号条件检查通过，但最新两小时窗口没有普通生成请求，不作当前生成可用结论。
- 发布前抽查六类管理 Read 接口各两次均为 200，复用连接后的样本约 229–274ms；请求列表首样本约 1452ms。包含客户端到公网的网络时间，样本量有限，不能据此宣称系统 Read 性能已全面优化。

## 回滚点

使用已部署 1.3.0 五个不可变镜像：后缀 `20261015-v1.3.0-release-e7fa98e856a4f758d1eefff95e8e9a716337776a`，并恢复原公共 automation 资源 patch；无需数据库降级或清理历史状态。后续源码修复应占用新的正式版本，禁止覆盖 1.4.0 制品。
