# Automation 流式取消、健康响应与 Arena 临时存储

## 目标与范围

基线 Source `516002a` / 生产 `0.30.0`。用户反馈 MiMo 桌面端“你好”无回复，并追加 Arena 临时存储不足、Automation 重启和探针超时。沿用已有合成推理、提交、候选及 GitOps 部署授权；修复候选占用 `0.30.1`。

- MiMo：核对 2026-10-13 10:00–10:09 UTC+8 的实际请求。`mimo-desktop` 的两条“你好”因合并 system/工具上下文超限，在约 1.6s 失败；同期另一 Key 的长生成与这些请求分别记录。只保存结构和数量，不保存用户原始提示、响应或密钥。
- Automation：修复 API stream 跨线程有界队列的背压及取消时同步关闭连接；进程清理的启动/周期扫描移出事件循环，关闭时等待已启动的清理完成。
- Arena：旧 Pod 明确因总 ephemeral storage 超过 1Gi 被驱逐；按节点余量给现有 Arena 容器增加临时存储预算，保持健康探针的当前超时门槛。

## 非目标与兼容

不裁剪或改写调用方 system、技能、工具和会话；不修改 Xiaomi 客户端配置、密钥、账号、付费模式、DB/Redis/PV 或高级模型探活策略。公开 Chat/Responses、错误模型、重试和工具执行边界保持。Automation 历史 exit 137 没有 OOMKilled 标记且 previous log 不可取，不能宣称历史根因已完全证明。

## 影响文件

`providers/api_transport.py`、`lifecycle/process_reaper.py` 与相关 Python 回归；统一版本文件；`deploy/gitops/` 的 Arena 资源 patch 及现有发布工作流；运行报告和任务板。

## 验收与验证

- 有界队列超过 512 个事件的突发输入不得丢失、乱序或遗失终止信号；慢消费者和取消均能释放生产端等待。
- 慢连接关闭和进程清理期间，真实 ASGI `/health/live` 仍能及时响应；关闭后的 worker/扫描完成可观察，失败不吞掉。
- 先保留旧实现的失败回归，再运行相关与完整 Automation、Backend、Web、Ruff 和版本/JAR 门禁。
- 候选验证后提升同一不可变镜像，核对 GitOps、版本/digest、Arena ephemeral budget、Pod/探针/日志以及合成 SDK 请求。普通推理与 PROBE 分开；高级模型不用于健康探活，Qwen 继续排除。
- 回滚点为 `0.30.0` 原四镜像及原资源 patch；不清除历史 Failed Pod/重启/探针事件来伪造恢复。

## 本次发布范围调整

七家原矩阵中 Arena/DeepSeek 被既有运行可用性阻断；用户明确授权“先发布稳定性修复，再处理两家故障”。此次 0.30.1 已按五家完整候选和公网验收提升同一 digest，保留七家失败报告、原始 PROBE/普通推理账本及两家故障项。安装版本、GitOps、资源/健康检查和 SDK/账本分别验证。后续稳定性与架构改进优先围绕两家实际故障，保持 OpenAI 契约、账号管理边界和高级模型探活保护。
