# 1.5.1 MiMo 流桥接修复发布执行计划

## 目标与授权

2026-10-18 用户明确要求“推送部署更新”，授权提交并推送现有 1.5.1 修复、沿既有 CI/GitOps 流程部署、检查运行版本与实际请求。时间统一 UTC+8。

## 范围与契约

- 发布已完成本地验收的 MiMo SSE 控制帧过滤、错误/结束处理、输出快照事件类型修复。
- Backend、Web、Automation 统一 1.5.1；公开 Chat/Responses 接口不变，无数据库迁移。
- Backend 与 Automation 成套更新，保持 DeepSeek、Qwen 停用和零副本；保留当前代理池与账号配置。
- 不新增定时生成探活；上线后优先利用自然业务请求核验正文、终止事件和失败分类。

## 发布前证据

- 基线：源码 `db4fdf5b0fc023987ebf21394cd88d2acba15b03` / 1.5.0，GitOps `e4139fbcf309b13c8f13d00ce86b4fa040ad7d44`，Argo Synced/Healthy。
- 本地后端 601 项中 596 通过、5 项需显式开启的 Grok 在线用例跳过；自动化 577 通过。
- Web 构建、Ruff、源码/JAR 版本契约通过。合成 SSE 验证包含控制帧、错误、推理分离、多行 data、结束后连接不关闭及取消释放。

## 执行与验收

1. 核对工作树仅包含本次修复，提交并推送 main；记录完整 source SHA。
2. 等待 `Build and deploy` 的质量门禁、六个不可变镜像、SBOM/provenance 及 GitOps 更新完成；失败时按具体错误处理，不复用或覆盖已生成制品。
3. 核对 GitOps 对应 source SHA 的镜像，等待 Argo Synced/Healthy；验证运行版本、Pod readiness/restarts、镜像 digest，检查 DeepSeek/Qwen 副本数。
4. 验证公网 health/ready、鉴权/OpenAI 错误契约与受保护的模型目录；读取新版本 MiMo 自然流量快照，检查 `eventType`、正文不含 dialogId/字面量结束标记、正确终止和错误分类。区分部署成功与厂家实际业务恢复，明确无样本或未完成的请求。
5. 将 CI、GitOps、运行态与流量证据写入发布验收报告，更新当前任务板；文档补记使用既有 docs/tasks 忽略规则，不重新构建同版本制品。

## 风险与回滚

- 滚动更新期间 Backend/Automation 可能短暂版本不一致，稳态验收必须在两端就绪后开始。
- 老快照无法可靠区分 reasoning 与最终正文，不能回填成新的成功依据；长耗时和 downstream_cancelled 需独立观察。
- 回滚恢复 GitOps 中 1.5.0 的原不可变镜像组合，保持 DeepSeek/Qwen 的零副本覆盖；无数据回滚步骤。回滚会重新出现已知 MiMo 控制帧污染，需如实记录。

## 状态

- 本地验收完成，已获得发布授权；CI/GitOps/生产验收待执行。
