# Agent 发布与 Read 性能核验

## 目标与授权

用户已授权“提交部署，然后测试”，并要求判断剩余能力、改进项和 Read 接口慢的原因。沿用现有 GitHub Actions → GitOps → Argo CD 生产发布链路，使用现有环境凭据完成验收；不输出或保存凭据。

## 范围

- 固化 OpenAI agent 升级源码，发布四个不可变应用镜像，核对迁移、运行版本、Pod 与 API。
- 按用户最新边界只完善 OpenAI Chat/Responses 常用接口 → 现有厂商 WEB 通道；工具由调用方执行，后端桥接定义、参数与结果。模型发现沿用 `/v1/models`。
- 0.24.0 本地候选验收后补齐发布配置，正式部署候选使用 0.24.1；镜像包含日期、版本、用途和源码 SHA。032 迁移仍记录引入该结构的 0.24.0。
- 真实厂商 WEB 的 Responses/Chat、SSE、function 工具循环、存储续接、资源权限和按模型能力支持的图片输入；标准 API/官方 SDK 是核心验收，既有 Codex 实验作为补充。
- 对管理 Read 和模型目录进行低并发采样，比较外网、集群内、SQL 和响应体成本，按当前证据定位瓶颈。
- 0.24.1 真实验收后：0.24.2 修复 MiMo/LongCat Runtime 强制工具对象解析；权限、Provider 状态和 Runtime rules 批量投影保持原 API 字段/顺序/历史上限；沿用 databaseExecutor 隔离同步 Controller；压缩 JSON，排除 SSE。
- 0.24.2 真实 SDK 验收后：0.24.3 在 CanonicalRequestParser 统一规范化 assistant/tool_calls 的 null 或缺省 content，保留 raw request、调用身份、文本/媒体和其他 role 的校验边界；补齐真实 Chat 工具结果回传回归。
- 0.24.3 产物核验后：0.24.4 的两种 Automation 应用镜像只重装当前项目包（不改变固定 browser-runtime 或重装运行依赖），构建时验证 pyproject、Python installed distribution 和 FastAPI version 一致，阻断旧安装包元数据进入新候选。

## 非目标与兼容

不扩展到全量 OpenAI 或厂商官方 API，不新增音视频/Files 管理等协议簇；namespace/custom 已有实现保留但不强制所有厂商支持。不承接 Codex 专属模型目录、配置或本机执行策略。管理 UI 风格和既有账号保持当前行为。发现性能缺陷后只修复已证实的瓶颈；生产候选源码变化继续占用新的 SemVer。

## 影响文件

既有协议/状态升级文件、版本配置、`.github/workflows/build-and-deploy.yml`、验收报告和任务记录；性能调查沿 Controller → Service → Repository、数据库计划、前端读取路径展开。

## 验收与回滚

- CI 全部门禁通过；镜像版本一致，GitOps Synced/Healthy，四个应用 Ready，迁移 tag/运行版本符合契约。
- 报告区分普通推理、真实 function 工具闭环、图片能力、扩展限制和未验证能力；WEB 文本 READY 不作为工具能力证明。
- 性能表包含端点、样本量、p50/p95、状态码、响应字节、测量位置；根因有调用链/SQL/运行态证据。
- 优先回滚前一套不可变镜像；032 additive schema 可保留。删除状态或回滚 schema 必须先停写并备份。

## 测试方式

Backend tests/bootJar、Automation pytest/ruff/lock、Web lint/build、版本契约；标准 OpenAI API/官方 SDK smoke（Codex 可选）；低并发 HTTP 计时和 PostgreSQL EXPLAIN/连接等待核验。
