# Agent 发布与 Read 性能核验

## 目标与授权

用户已授权“提交部署，然后测试”，并要求判断剩余能力、改进项和 Read 接口慢的原因。沿用现有 GitHub Actions → GitOps → Argo CD 生产发布链路，使用现有环境凭据完成验收；不输出或保存凭据。

## 范围

- 固化 OpenAI agent 升级源码，发布四个不可变应用镜像，核对迁移、运行版本、Pod 与 API。
- 0.24.0 本地候选验收后补齐发布配置，正式部署候选使用 0.24.1；镜像包含日期、版本、用途和源码 SHA。032 迁移仍记录引入该结构的 0.24.0。
- 真实厂商 Responses/Chat、SSE、工具循环、存储续接、资源权限和实际 Codex 客户端验收。
- 对管理 Read 和模型目录进行低并发采样，比较外网、集群内、SQL 和响应体成本，按当前证据定位瓶颈。
- 0.24.1 真实验收后：0.24.2 修复 MiMo/LongCat Runtime 强制工具对象解析；权限、Provider 状态和 Runtime rules 批量投影保持原 API 字段/顺序/历史上限；沿用 databaseExecutor 隔离同步 Controller；压缩 JSON，排除 SSE。

## 非目标与兼容

不改管理 UI 风格，不批量修改账号，不绕过客户端执行策略，不以健康检查代替 agent 验收。发现性能缺陷后只修复已证实的瓶颈；生产候选源码变化继续占用新的 SemVer。

## 影响文件

既有协议/状态升级文件、版本配置、`.github/workflows/build-and-deploy.yml`、验收报告和任务记录；性能调查沿 Controller → Service → Repository、数据库计划、前端读取路径展开。

## 验收与回滚

- CI 全部门禁通过；镜像版本一致，GitOps Synced/Healthy，四个应用 Ready，迁移 tag/运行版本符合契约。
- 报告区分普通推理、真实工具闭环、客户端兼容和未验证能力。
- 性能表包含端点、样本量、p50/p95、状态码、响应字节、测量位置；根因有调用链/SQL/运行态证据。
- 优先回滚前一套不可变镜像；032 additive schema 可保留。删除状态或回滚 schema 必须先停写并备份。

## 测试方式

Backend tests/bootJar、Automation pytest/ruff/lock、Web lint/build、版本契约；现有官方 SDK/Codex smoke；低并发 HTTP 计时和 PostgreSQL EXPLAIN/连接等待核验。
