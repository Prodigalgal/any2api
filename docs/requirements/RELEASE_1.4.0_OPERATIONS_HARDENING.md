# 1.4.0 发布修复与运行验收

## 目标与范围

修复 1.4.0 内部验证码模型选择功能的发布门禁，缓解公共 automation 的 CPU 节流，并停止对已停用厂商专用服务的周期目录请求。用户已明确授权修复后发布 1.4。

2026-10-15 21:39–21:43 UTC+8 实查：生产五个应用 Deployment 为 1.3.0，全部 Ready、零重启；1.4.0 CI 因 FastAPI 仍声明 1.3.0 而失败，另有两个 Python 文件未通过格式检查。公共 automation 使用满 1 核，一秒采样的十个 CPU 周期全部节流；保留就绪探针超时事件。Qwen 零副本且未启用，后端仍每分钟访问其专用服务。

## 非目标与兼容

保留 Chat/Responses 契约、入库后一次推理验证、现有账号/密钥和 self proxy 四节点绑定。Qwen 继续零副本，不纳入发布验收。Arena 反爬、Grok 上游流错误和 DeepSeek 无启用账号分别记录，不以 Pod Ready 代替厂商生成验收，不增加付费探活或自动启用账号。

## 影响文件与取舍

- `automation/any2api_automation/main.py`、验证码策略/路由及其回归：对齐版本、格式，覆盖指定模型、全局默认和随机回退。
- `AutomationProviderCatalog` 及其测试：沿用已有 `ProviderInstallationCatalog` 的启用状态快照，只跳过停用厂商的专用目录地址；公共地址继续发现，厂商重新启用后恢复发现，不增加数据库访问体系。
- `deploy/gitops/automation-runtime-resources.yaml`：公共 automation CPU limit 从 1 核提升到 2 核，request 从 200m 到 500m；维持内存、探针及滚动策略。当前配额 limits CPU 7/10 核，变更后 8/10 核；节点当前 CPU 约 36%/48%，具备余量。
- 现有任务板与发布报告：保留源码、CI、GitOps、镜像 digest、实际运行版本和遗留问题的独立证据。

## 验收与测试

1. 版本契约与 JAR 校验、Automation Ruff/完整 pytest、Backend 测试、Web lint/build 通过。
2. 停用专用目录地址不产生请求；启用后恢复请求；共享地址只请求一次；现有目录解析行为不变。
3. 验证码策略的模型字段在 Java/Python 间保持，任务指定优先于全局默认，显式 random 回到原随机路由；禁用 AI/外部模式不误发内部请求。
4. 发布同一不可变 1.4.0 制品，确认 Argo Synced/Healthy、五个应用实际版本/digest、Qwen 零副本、资源配额及 CPU 节流采样、探针、受保护公共 API 与只读管理 API。
5. 不发起厂商生成探活；普通流量、客户端取消、账号验证和生命周期分别报告。健康/模型列表不作为真实生成成功证明。

## 回滚

回滚到 1.3.0 `20261015-v1.3.0-release-e7fa98e856a4f758d1eefff95e8e9a716337776a` 五个不可变镜像和原资源 patch；无数据库迁移，无账号/密钥回滚。若构建后需修改源码，则占用下一个正式补丁版本，不覆盖候选制品。

## 交付结果

1.4.0 已发布：源码 `2e4a080`、CI `38746417044`、GitOps `7a58b93`，五个应用实际版本/digest、Argo、受保护 API、代理绑定及短时 CPU/探针采样均已验证。Backend 583 passed/5 conditional skipped、Automation 572 passed、Web/Ruff/版本门禁通过。滚动期间发生过旧 Pod 占额引发的短暂创建阻塞，已自动恢复，保留事件；Arena/Grok/DeepSeek 厂商问题继续记录。详见[发布报告](../reports/RELEASE_1.4.0_RUNTIME_ACCEPTANCE_2026-10-15.md)。
