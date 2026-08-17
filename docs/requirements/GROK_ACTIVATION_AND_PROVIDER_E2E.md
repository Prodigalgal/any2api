# Grok 激活入池与全厂商集群验收

## 目标

- 将历史迁移的 `PENDING + disabled` 账号接入可恢复、可观测的激活流程。
- Grok Build、Grok Web、Grok Console 分别通过真实模型请求后独立入池。
- 在隔离 Kubernetes namespace 中对所有已安装推理厂商执行真实探针。

## 范围

- 新增单账号“激活入池”管理 API 与后台入口。
- 根据厂商 capability 选择 `reauthenticate` 或 `probe`，并支持 1 秒至 7 天的分散调度。
- 迁移工具将第二阶段从误导性的同步 probe 调用改为显式 activation 调度。
- 使用独立 PostgreSQL、Redis 和最小账号样本执行集群验收。

## 非目标

- 不在本任务中批量激活生产环境全部 Grok 账号。
- 不把注册成功、SSO 存在或 keepalive 成功视为推理就绪。
- 不在验收报告中记录邮箱、外部账号 ID、Cookie、Token 或其他凭据。

## 影响文件

- `backend/.../lifecycle/AccountActivationService.java`
- `backend/.../api/admin/AdminAccountController.java`
- `web/src/components/accounts.tsx`
- `web/src/components/account-detail-dialog.tsx`
- `tools/migration/migrate_legacy_accounts.py`
- 集群临时 namespace 与验收报告

## 验收标准

1. `BANNED` 账号不能被激活，调度窗口越界被拒绝。
2. 支持重新认证的待就绪账号先排队重新认证；其余账号排队真实探针。
3. 账号只有在真实探针返回非空模型输出后才变为 `ACTIVE + enabled`。
4. Grok 三个通道分别产生独立通过或失败结论，不相互代替。
5. DeepSeek、GLM、Grok Build、Grok Web、Grok Console、LongCat、MiMo、MiniMax、Qwen 均有集群实测结果。
6. 测试 namespace 不连接生产 PostgreSQL/Redis，不复制完整生产账号库。

## 测试方式

- Backend 单元测试覆盖激活动作选择、封禁和边界窗口。
- Automation 执行 format、lint 和完整 pytest。
- WEB 执行 lint 与 production build。
- 0.6.0 不可变镜像通过 CI 后，在临时 namespace 执行逐厂商账号探针并保存脱敏结果。
