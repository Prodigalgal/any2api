# GLM / LongCat K8S Runtime 真实验收补充记录（2026-09-10）

## 结论

本记录补充 `0.14.0` 候选在 K8S 上的 GLM 与 LongCat 真实运行证据。请求仍通过统一 Runtime、Camoufox/页面同源 Web API 和账号租约执行，不接入厂商公开渠道 API。

- GLM：文本和视觉图片链路已打通；模型探针为 `READY`、熔断器为 `CLOSED`，但 24 小时运行窗口的聚合状态仍为 `DEGRADED`，所以只认定为“功能通过、稳定 Ready 观察中”。
- LongCat：图片、PDF、有效 DOCX 的非流式链路已通过；图片、PDF、DOCX 的 SSE 链路已取得真实完成事件，其中图片和 PDF 已核对 `[DONE]`。TXT 能上传但上游当前不解析其内容，不把上传成功冒充为文档读取成功。

## 制品与 K8S 运行态

| 项目 | 证据 |
| --- | --- |
| namespace / context | `any2api` / `kubernetes-admin@sg-osaka-dualstack` |
| 源码提交 | `99449096e5f5ca4ad57811cc205fdbcf48db2ed8` |
| CI | GitHub Actions `34476956811`，质量检查、三类镜像构建、GitOps 更新成功 |
| 推理镜像 GitOps | `ac420b04564ad6088c27625805324be723f3b902` |
| 配额修复 GitOps | `964247342a3310688ac06647bbaac2b6d44e9658` |
| Argo CD | `Synced / Healthy / Succeeded` |
| Pod | server、automation、web、PostgreSQL、Redis 均 `Ready=true`，业务 Pod 重启为 0 |
| 当前配额 | `requests.memory=8Gi/limits.memory=20Gi`，使用量约 `3456Mi/9600Mi` |

配额修复前的滚动发布曾出现 `FailedCreate`，原因是旧 server 与新 server 重叠时，automation `6Gi` limit 无法在 `16Gi` namespace limit 内创建；这不是 `OOMKilled`。配额调整后资源对象已由 Argo 应用，后续发布仍需继续观察。

## GLM 真实证据

### 模型目录

当前 `/v1/models` 返回：

| 模型 | 可用 | 输入能力 | 账号 | 探针 | 熔断器 | 运行窗口 |
| --- | --- | --- | ---: | --- | --- | --- |
| `glm/glm-4.6v` | `true` | `text,image` | 23/23 | `READY` | `CLOSED` | `DEGRADED`，15 次滚动请求，成功率约 86.7% |
| `glm/glm-5.2` | `true` | `text` | 23/23 | `READY` | `CLOSED` | `DEGRADED`，18 次滚动请求，成功率约 77.8% |

GLM-4.6V 的图片输入来自官方目录 `capabilities.vision=true`；GLM-5.2 的官方元数据为文本模型。文档输入不是 GLM 当前声明能力，应在请求校验阶段明确拒绝。

### 请求

- `glm/glm-5.2`：文本非流式 HTTP 200，文本 SSE HTTP 200、存在数据和 `[DONE]`。
- `glm/glm-4.6v`：纯图片输入非流式 HTTP 200，纯图片输入 SSE HTTP 200、存在识别结果和 `[DONE]`。

因此 GLM 的业务功能链路通过，但运行窗口尚未达到稳定 Ready 门槛；历史失败样本不能通过清理或降低阈值隐藏。

## LongCat 真实证据

适配器在存在图片或文档时自动使用官方 `agentId=multiModal`；纯文本仍使用 `agentId=1`。该行为已由 Python Runtime、Java mapper 和契约测试覆盖。

| 输入 | 模式 | 结果 |
| --- | --- | --- |
| 32x32 纯红 PNG | 非流式 | HTTP 200，模型返回“红色” |
| 32x32 纯红 PNG | SSE | HTTP 200，返回“红色”数据和 `[DONE]` |
| 有效 PDF | 非流式 | HTTP 200，返回文件内唯一标识 |
| 有效 PDF | SSE | HTTP 200，返回文件内标识分片和 `[DONE]` |
| 有效 OOXML DOCX | 非流式 | HTTP 200，返回文件内唯一标识 |
| 有效 OOXML DOCX | SSE | HTTP 200，返回文件内标识分片和 `[DONE]` |
| TXT | 非流式 | 上传成功，但上游回复无法读取附件；不计入内容读取通过 |

LongCat 当前目录能力为 `text,image,file`，模型目录账号为 26 个 eligible、25 个 available、0 个 quota-limited，探针 `READY`、熔断器 `CLOSED`。运行窗口仍为 `DEGRADED`，因此 LongCat 先标记为“图片/PDF/DOCX 功能通过、完整文件扩展稳定性观察中”。音频和视频不在本轮聊天输入范围。

## 遗留风险与下一步

1. GLM、LongCat 的健康窗口仍需自然滚动到稳定阈值，不能只凭一次成功请求标绿。
2. LongCat TXT 以及 PDF/DOCX 之外的官方可上传扩展需要逐项复测；在没有内容读取证据前不扩大声明。
3. 配额已为滚动发布预留余量，但下一次镜像更新仍需核对 `FailedCreate`、Pod Ready、重启数和 `OOMKilled`。
4. API Channel 仍后置，Grok 三通道仍排除在本轮范围之外。
