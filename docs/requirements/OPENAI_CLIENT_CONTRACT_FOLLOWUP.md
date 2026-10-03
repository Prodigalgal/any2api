# 统一 OpenAI 客户端契约与厂商适配补齐

## 目标和范围

以已部署的 0.25.7 为基线，让现有厂商 WEB 在同一 OpenAI Chat/Responses 契约下支持标准模型详情和可校验的严格函数参数。本轮为新增向后兼容能力，候选占用 0.26.0。

- 新增统一和厂商前缀的 `GET models/{model}`，返回与目录一致的模型对象、能力和运行态；支持带斜线的模型 ID，Key 权限过滤后不存在的模型返回 OpenAI JSON 404。
- `strict:true` 的 function schema 在账号租约之前校验；厂商仍使用已有 WEB 函数桥接。所有厂商产生的严格工具参数经过同一网关检查，合格后才向客户端输出工具事件，不合格返回 `tool_call_generation_failed`，不得发送不合格工具参数、补造字段或伪造成功。
- 第一轮严格 schema 支持 object、array、基础类型、nullable、enum、anyOf、局部非循环引用、数值/长度约束。必须声明 object 的 `additionalProperties:false` 和全部必填字段。复杂关键词、正则、format、外部引用、循环引用明确拒绝；schema/参数大小、深度、分支和缓存均有界，校验器不访问外部资源。
- 现有未声明 strict 和 `strict:false` 的行为保留；Responses 缺省 `store:false`、24 小时保留策略、已有 Key/厂商权限、媒体、搜索、推理、取消和失败语义保留。

## 非目标

不扩展 OpenAI 全部协议；不新增 Conversations、WebSocket、background、compact、hosted tools 或结构化文本输出协议。本轮不发布 Cloudflare Worker，不处理 Qwen 账号、不修改原始图片或调用方工具执行方式。

## 影响文件

`protocol` 的严格 schema/工具事件校验、`ProviderRequestValidation`、现有 MiMo/LongCat/共享函数桥接的 strict 前置拒绝、`ModelCapabilityContract`、`ModelsController`、`ApiExceptionHandler`、对应测试与 SDK smoke、统一版本文件和接入文档。

## 验收和验证

- schema 成功、类型/必填/额外字段/enum/嵌套/引用失败、危险或过大 schema、并行/分段工具事件、上游失败和缺失终态分别验证；不合格严格参数不出现在客户端输出。
- 模型详情覆盖根路径和厂商路径、编码斜线、不存在、Key 隔离、禁用模型、能力/运行态字段一致；官方 SDK `models.retrieve()` 可以读取。
- 运行 Backend 测试/构建、版本契约、Automation 回归与 Web 构建门禁；用真实 HTTP fixture 验证官方 SDK 的 strict 函数解析和失败。
- 已有提交/部署授权下，提交唯一候选、跟踪 CI/GitOps/Pod，并对 Qwen 以外七家所选模型进行有限真实 strict 工具调用/结果续接验证；失败尝试独立记录，不把代码能力声明当成厂商成功证明。

## 兼容和回滚

无 schema/凭据迁移。新增模型详情接口与显式 strict 行为不影响已有普通请求。严格函数调用需要缓冲工具事件到参数完成并通过校验，普通文本/推理和未启用 strict 的路径不增加缓冲。若候选异常，恢复 0.25.7 四组件不可变镜像，保留现有部署调度、namespace 和分发 Key；严格函数能力随回滚撤回。
