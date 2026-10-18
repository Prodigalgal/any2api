# DeepSeek 退出运行范围（2026-10-18）

## 目标与范围

用户要求核对各厂商当前状态，并将 DeepSeek 拔出。2026-10-18 09:57 UTC+8 实查：生产为 1.4.3，DeepSeek 提供商已停用、账号 0、注册计划停用、无活动注册作业/调度动作，但专用 automation Deployment 仍运行 1 副本。本轮补齐持久化退出运行，不删除厂商历史。

## 非目标

工作区存在用户未提交的 MiMo v3、验证码模型链、DeepSeek 注册能力移除等改动。本轮不覆盖、提交或发布这些源码，也不发起厂商生成探活。Arena/Grok 故障及公共 automation OOM 另列运行风险。

## 影响与实施

- 在独立 GitOps checkout 的 `any2api/oracle/kustomization.yaml` 增加 `replicas` 项，将 `any2api-automation-deepseek` 固定为 0；该覆盖独立于 CI 复制的 Deployment 清单，可防止后续镜像发布将其重新拉起。
- 沿用现有已停用的 provider 和 registration schedule；无账号、活动作业和调度动作时不做额外数据库修改。
- 这是现有服务的运行启停，不构建新的软件候选；其余服务继续运行已发布 1.4.3 的原不可变镜像。

## 验收与验证

1. 修改前后 Kustomize 渲染对比，仅 DeepSeek Deployment 的 `spec.replicas: 1 -> 0` 发生变化。
2. GitOps 提交同步后 Argo Synced/Healthy，DeepSeek desired/ready 为 0、无残留 Pod。
3. DeepSeek provider/schedule 停用，账号/活动注册作业/调度动作均为 0；公网目录与模型列表不暴露 DeepSeek，其余六家模型列表正常；不生成文本。
4. 原有工作区改动逐文件 hash 保持；不清理历史 Failed Pod、OOM/驱逐记录或运行日志。

## 回滚

移除该 `replicas` 项或将 count 恢复为 1，可恢复专用容器。提供商与注册计划的再次启用需单独决定，不随容器恢复自动启用。
