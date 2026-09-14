# 全厂商全链路打通任务规格

## 目标

为业务范围内的 Arena、DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 打通完整链路：
注册（Runtime-only）、重新认证、保活、打卡（仅 MiniMax）、模型发现、文本双通道、
多模态双通道（厂商已声明能力处），并形成可复现的 K8S 真实验收证据。

Grok / Grok Console / Grok Web 不在本轮业务范围。

## 范围

- 生命周期：`register` / `reauthenticate` / `keepalive` / `daily_checkin`（Runtime-only）
- 推理：`chat` / `model_discovery` 的 Runtime 与 API 双通道
- 多模态：图片；文件（仅声明 `FILE_INPUT` 的 LongCat、Arena）
- 模式策略：KEY 级 `transportMode`；`AUTO` 为 API 优先并可按分类回退 Runtime
- 环境：先 `any2api-e2e-20260817`，证据闭环后再同步生产 `any2api`

## 非目标

- 不绕过验证码、风控、额度和每日打卡限制
- 不把生命周期动作迁移到 API
- 不把未通过真实验收的模型/能力标为 Ready
- 不处理 Grok 三通道

## 全链路定义（每家验收清单）

| # | 用例 | 通道 | 验收标准 |
|---|---|---|---|
| 1 | register | Runtime | 账号落库 `ACTIVE/enabled`，可租约 |
| 2 | keepalive | Runtime | `SUCCEEDED`，不伪造可用 |
| 3 | reauthenticate | Runtime | 过期/失效账号恢复或明确失败分类 |
| 4 | model_discovery | Runtime+API | 非空目录，`(provider,upstream)` upsert |
| 5 | chat 文本非流式 | Runtime+API | HTTP 200，有 choice/content |
| 6 | chat 文本 SSE | Runtime+API | HTTP 200，多数据帧 + `[DONE]` |
| 7 | chat 图片非流式 | Runtime+API | 声明 vision 的模型返回描述 |
| 8 | chat 图片 SSE | Runtime+API | 同上，流式闭环 |
| 9 | chat 文件非流式/SSE | Runtime+API | 仅 LongCat/Arena，内容可被模型读取 |
| 10 | AUTO 回退 | AUTO | API 分类失败后 Runtime 成功 |
| 11 | 显式 API 失败关闭 | API | 不静默改走 Runtime |
| 12 | daily_checkin | Runtime | 仅 MiniMax，自然调度真实结果 |

## 现状基线（2026-09-13 盘点）

### 生产账号（any2api）

| Provider | ACTIVE/enabled | 其他 |
|---|---:|---|
| arena | 7 | 2 EXPIRED |
| deepseek | 16 | — |
| glm | 23 | — |
| longcat | 26 | — |
| mimo | 57 | — |
| minmax | 7 | 29 PENDING, 1 DISABLED |
| qwen | 12 | 34 PENDING, 1 EXPIRED |

### E2E 账号（any2api-e2e-20260817）

| Provider | 状态 |
|---|---|
| deepseek | ACTIVE 1 |
| glm | DEGRADED 1 |
| longcat | ACTIVE 1 |
| mimo | ACTIVE 1 |
| minmax | PENDING 1（无可用） |
| qwen | PENDING 1（无可用） |
| arena | 无账号行 |

### 通道配置

- E2E：`deepseek/glm/longcat/mimo/qwen` = `API`；`arena/minmax` = 默认
- 生产：7 家均未写 `inference_transport_mode`（走 AUTO 默认）

### 2026-09-14 生产真实验收矩阵

图例：`PASS` 真实成功；`FAIL` 有明确错误；`STALE` 需新鲜探针；`N/A` 未声明能力。

| Provider | keepalive | 文本 collect | 文本 SSE | 图片 collect | 图片 SSE | API 通道 | 备注 |
|---|---|---|---|---|---|---|---|
| DeepSeek | PASS | PASS | PASS | N/A | N/A | API | text-only；首字节偏慢 |
| MiMo | PASS | PASS | PASS | PASS | PASS | API | 图片走 API 成功 |
| LongCat | PASS | PASS | PASS | FAIL | FAIL | API+Runtime | `empty_model_response`，上传后无输出 |
| GLM | PASS | PASS | PASS | FAIL | — | API 文本 OK | Runtime 图片 RuntimeError；API 图片 HTTP 400 |
| Qwen | PASS | PASS | — | — | — | API | 文本通；探针/上游偶发 502 |
| Arena | PASS | PASS(Runtime) | — | STALE | — | API 401 | Runtime 文本通；API `User not found` |
| MiniMax | 历史 PASS | STALE | — | — | — | 签名 profile | 探针 `provider_upstream_error` |

### 当前阻断点

1. **模型可用性门禁**：探针新鲜度仅 30 分钟。GLM/Arena/MiniMax 无近期成功 usage 时必须先 `POST /api/admin/v1/models/probe`。
2. **LongCat 图片**：Runtime/API 均 `empty_model_response`，需查上传 `files` 与上游 completion。
3. **GLM 图片**：Runtime 页桥流失败；API `/api/v1/files/` 返回 400。
4. **Arena API 鉴权**：Runtime cookie 不能直接用于 Web API，需补齐 API 凭据传播。
5. **MiniMax API**：签名 salt/yy_salt/version_code 已写入 automation 默认值；运行态曾缺配置导致 profile unavailable。
6. **Qwen probe vs chat**：chat 可成功，但 model probe 可能走 Runtime 并失败。

## 实施阶段

### Phase 0 — 现状盘点与缺口矩阵

- [x] 读生产/E2E 账号、模型、provider 配置
- [x] 写出逐厂商 × 逐用例矩阵
- [x] 确认内部 token / admin / public key 可用
- [x] 建立可重复 smoke 脚本入口（`scripts/fullchain_smoke.py`、`scripts/admin_login_probe.py`）

### Phase 1 — 公共层与代码级缺口

- 核对 7 家 API/Runtime binding 覆盖声明的 inference_actions
- 核对 LongCat `model_discovery` 是否缺失（当前仅 `chat`）
- 核对多模态在 API channel 的上传路径
- 补齐失败分类、channel 日志、契约测试

### Phase 2 — 逐厂商真实验收

顺序：DeepSeek → MiMo → LongCat → GLM → Arena → MiniMax → Qwen

每家完成：keepalive → discovery → 文本双通道 → 多模态双通道 → AUTO 回退证据。

### Phase 3 — 缺账号厂商注册补齐

对 E2E 缺账号的 Arena/MiniMax/Qwen 走 Runtime 注册；生产只补必要账号，不批量刷号。

### Phase 4 — 发布与生产同步

- GitHub Actions 质量检查
- E2E 全绿后同步生产
- 版本按 SemVer 递增

## 验收标准

1. 七家均有：Runtime keepalive + 文本非流式/SSE 真实成功
2. 声明图片的厂商：Runtime 与 API 图片非流式/SSE 真实成功（或明确厂商阻断证据）
3. LongCat/Arena 文件输入：内容读取真实成功或明确未就绪
4. AUTO 回退与显式 API 失败关闭均有日志/请求证据
5. MiniMax daily_checkin 有自然调度真实结果
6. CI 三端 quality + 镜像构建通过
7. 生产 Pod Ready、零重启、无新增 OOM

## 验证约束

- 本机不执行完整编译构建；代码质量由 GitHub Actions 执行
- 本机可执行：静态检查、git diff、K8S 查询、集群内 smoke
- 真实验收优先 E2E namespace，生产变更需独立确认
