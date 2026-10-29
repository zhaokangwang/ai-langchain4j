# 第四步：实时回答与执行事件

重启后打开 http://localhost:18080/ ，Ctrl+F5 刷新。页面默认勾选“实时回复”，取消勾选即可使用原来的 JSON 模式。原有接口全部保留。

## 新接口

`POST /api/chat/stream`，请求头 `Content-Type: application/json`、`Accept: text/event-stream`，请求体与 `/api/chat` 相同：

```json
{"message":"我收到的键盘坏了，想退货","conversationId":null,"requestId":"learning-stream-001"}
```

响应为 UTF-8 SSE，浏览器通过 fetch 读取 POST 响应流。不要使用仅支持 GET 的 EventSource 直接调用。Swagger UI 可用于查看接口定义；实时阅读请使用首页。

| 事件 | 含义 |
| --- | --- |
| accepted | 请求和会话 ID；cached=true 表示复用已完成结果 |
| authorization | 第三阶段新增：本次请求确认的后端身份、数据范围及授权技能名单 |
| routing | 第二阶段的业务路由原因、候选技能及已确认对象；直接拒绝或追问时可以跳过模型事件并进入 done |
| model_start | 开始第 round 轮模型调用 |
| delta | 该轮新增文本 text，由真实模型流提供 |
| model_complete | 该轮完整返回，包含 text 和 hasToolCalls |
| tool_start | 工具开始执行，包含调用 id、name 和 round |
| tool_complete | 工具结果已经记录，包含状态、技能名、耗时 |
| heartbeat | 每 15 秒连接心跳，不代表模型进度 |
| done | 最终回答已保存；数据结构与原 ChatResponse 相同 |
| error | 请求未正常结束，包含 status、message、requestId |

这些是执行事件，不展示模型内部思考过程。模型可能先生成文字再要求调用工具，因此当前轮文字是临时内容；进入下一轮时前端会替换它，最终以 done 的回答为准。工具事件由实际执行路径产生，不使用定时器模拟进度。

`POST /api/chat/stream/{requestId}/cancel` 请求停止生成，返回 cancelRequested。停止按钮同时中止浏览器读取，后端尽力取消上游订阅。已经完成的工具操作、结果和模型费用不会撤销；完成与取消并发时，应以历史记录为准。当前进程使用启动配置指定的演示身份，不适用于未加入身份认证的公网多用户环境。身份与技能权限见 [SKILL-AUTHORIZATION.md](SKILL-AUTHORIZATION.md)。

## 保存、异常和兼容性

- 新旧接口共享会话、requestId 去重、模型记录、工具记录和数据库；已完成请求可以跨接口复用，不重复调用模型。
- done 只在完整回答及上下文写入数据库后发送。连接在 done 前断开不能推断成功，先查看历史记录；前端不自动重连或重发。
- 取消、超时和断开时执行记录标为 INTERRUPTED；已收到的模型文字保存为带 incomplete=true 的审计片段，不作为成功回答加入后续上下文。前端临时显示“未完成”片段，刷新后可在执行记录查看。
- 上游异常标为 FAILED，对外返回通用错误，完整问题可通过后端日志和执行记录排查。部分失败可以沿用原重试机制；主动停止后重新发送使用新的请求 ID。
- 单轮流式等待上限默认 90 秒，可设置 `app.chat.stream-round-timeout-ms`；模型 HTTP timeout 为 60 秒，SSE 连接上限为 10 分钟。线程池和事件缓冲区均有界，队列满时拒绝或终止，不无限堆积。
- 原 `/api/chat`、技能示例接口和历史接口保留原行为，不需要迁移数据库。流式模型与普通模型共用现有 DeepSeek 地址、模型名和环境变量 Key；前端不接触 Key。

## 文件和验证

### AsyncNotSupportedException 排查

当前缓存版本的 SpringRestClient 只实现回调式流输出，直接使用 Flow.Publisher 会返回 AsyncNotSupportedException。这不是 Windows 端口或 API Key 问题。客户端在尚未收到任何事件且明确提示不支持异步接口时，切换到 `chat(request, StreamingChatResponseHandler)` 回调式流接口；仍然逐段输出，不改成普通完整返回，也不对网络失败或已经开始的请求重试。无需新增 WebFlux 依赖。

`StreamingTransportIntegrationTest` 使用真实自动配置的 OpenAI 模型和 SpringRestClient，连接本地 HTTP 模拟供应商，验证中文片段、done、持久化及上游只收到一次请求。修复后需要重新编译并重启后端。

`ChatStreamController` 管理 SSE、心跳和取消，`ChatStream` 传递事件及取消状态，`RecordedStreamingModelClient` 读取 LangChain4j 的 StreamingChatModel 并复用审计，`CustomerChatService` 继续负责原有模型与工具循环。前端 `api.js` 解析 SSE，`chat.js` 管理实时回答和停止操作。

`StreamingChatIntegrationTest` 用模拟模型验证片段提前可见、工具事件、持久化、中文编码、取消、超时、异常和旧接口兼容；`node --test scripts/frontend-check.cjs` 验证 SSE 分包、UTF-8、停止及页面状态。这些测试不消耗真实模型额度；真实 DeepSeek 联调需在本机配置 Key 后进行。
