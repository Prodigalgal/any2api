# 厂商官方浏览器传输

## 目标

通过浏览器注册或重新授权的账号必须持久化认证状态与浏览器身份。对于请求头、签名、
工作量证明或会话状态随官方前端变化的厂商，推理请求应调用当前官方页面运行时，避免每次
上游改版都重新打包发布。

## 范围

- 持久化 Cookie、localStorage、IndexedDB、设备信息、运行时指纹和 Camoufox 完整生成配置。
- 将账号认证状态和语义化对话上下文注入对应官方页面 origin。
- 由官方前端函数生成应用请求头、签名和工作量证明。
- 注册、推理与重新授权期间保持账号、浏览器身份和代理出口亲和关系稳定。
- 通过现有加密 `credential_patch` 契约回写变化后的浏览器状态。
- Java 继续负责 API 校验、上下文长度策略、账号租约和规范事件映射。
- Camoufox 为首选运行时并回放完整生成配置；Patchright 作为后备，只承诺原生
  `BrowserContext` 支持的 UA、locale、timezone、viewport 和 screen 等字段稳定复用。

## 非目标

- 提供可执行任意 Header 或 JavaScript 的管理端编辑器。
- 官方模块发现失败后静默退回复制的签名算法。
- 为架构形式统一而把稳定、受支持的 API 强制迁入浏览器。
- 未通过真实登录态流式验证时宣称厂商链路可投入生产。

## 本期范围

- MiMo 对话与模型发现迁移到当前官方 Rspack 请求对象。
- GLM 的会话创建、请求上下文、签名和 completion 迁移到当前官方前端 bundle；
  Java 不再保留滚动的 frontend version 和 signature key。
- MiMo、GLM、DeepSeek、LongCat 注册持久化确定性的代理亲和 key；发生注册重试时同时记录
  成功节点的 `proxy_node_offset`，模型发现、重新授权和推理复用同一节点。
- 审计 DeepSeek、LongCat、Grok Web、Grok Console 和 Grok CLI，并按真实证据分类。
- 保留既有 Java 请求校验、模型上下文限制、媒体上传和事件解码行为。

## 影响模块

- `automation/providers/official_browser.py`：账号级浏览器会话恢复、Camoufox 配置回放、
  厂商存储隔离和认证状态回写。
- `automation/providers/mimo_browser.py`：MiMo 官方 Rspack 模块发现、模型配置与 SSE 传输。
- `automation/providers/glm_runtime.py`：GLM 当前 bundle 函数发现、签名和流式传输。
- `backend/transport/OfficialBrowserTransportClient.java`：Java 到 Python 的语义命令契约。
- `backend/provider/mimo`、`backend/provider/glm`：只发送语义请求，不再生成滚动请求头。

## 验收标准

- MiMo 通过行为特征发现官方模块，不固化数字 Rspack module ID。
- GLM 通过行为特征发现官方函数，不固化混淆函数名、frontend version 或 signature key。
- MiMo、GLM 的真实页面只读 smoke 能返回预期结构并生成 `browser_execution_context`。
- 旧账号在首次成功对话时生成并持久化 `browser_execution_context`。
- 恢复上下文前过滤非本厂商 Cookie 和 storage origin。
- 注册、模型发现、推理和重新授权复用账号级代理亲和 key。
- 浏览器启动、模块发现和流式请求失败都输出结构化错误，不记录认证信息或请求正文。
- Python lint/测试、Java 测试、前端 lint/build 全部通过。
- MiMo、GLM 的真实账号完整 SSE 仍是生产发布硬门禁。
- DeepSeek、LongCat、Grok Console 未取得认证流证据前保留原链路，不做破坏性替换。

## 回滚

本期不修改数据库结构。回滚点是上一不可变版本镜像；MiMo、GLM 的 Java/Python transport
必须成对回滚，不能只回滚一端。新增的 `browser_execution_context` 和
`proxy_affinity_key` 属于兼容字段，旧版本会忽略它们。
