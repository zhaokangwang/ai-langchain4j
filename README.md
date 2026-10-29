# Spring Boot + LangChain4j 入门实验

代码质量检查：已接入项目定制 Checkstyle，随 Maven 编译和测试自动执行。规则、命令与 IDEA 配置见 [代码质量约束](docs/CODE-QUALITY.md)。

现有后端已完成职责分层整理：SQL 归入 Repository，共享 DTO 归入业务包，聊天工具执行独立，路由复杂度豁免已移除。详见 [重构记录与验证](docs/REFACTORING.md)。

新增：数据库订单报表，可筛选订单、查看汇总与分页明细、下载 Excel，并从聊天生成报表。启动后访问 [报表页面](http://localhost:18080/reports.html)。演示数据集中在 2026 年 10 月，默认客服身份仅可见 A1001。详见 [报表说明与验证](docs/REPORTS.md)。

最新进度：多角色技能第三阶段已完成。页面显示当前身份、授权技能、实时业务路由及实际工具执行，历史记录按尝试展示身份/路由摘要，并区分后端直接回复与模型参与。角色仍由后端启动配置指定，默认买家 demo-user。见 [页面与验证步骤](docs/SKILL-UI.md)、[角色权限说明](docs/SKILL-AUTHORIZATION.md) 和 [业务路由说明](docs/SKILL-ROUTING.md)。

本项目使用 Java 17、Spring Boot 3.5.7 和 LangChain4j Spring Boot starter 1.22.0-beta32。版本固定以便复现，starter 的 beta 后缀是其发布版本的一部分。

已接入 **H2 文件数据库 + Spring JDBC + Flyway**，默认保存到启动目录下的 `data/customer-service.mv.db`。会话、消息、模型/工具调用和请求状态持久化；页面支持历史切换、重命名、归档恢复及执行记录查看。数据库位置、重启续聊、去重规则和查询 SQL 见 [文件数据库说明](docs/PERSISTENCE.md)。

## 1. 它解决什么问题

Spring Boot 负责应用启动、依赖注入、HTTP 接口和配置管理；LangChain4j 负责将 Java 业务连接到大语言模型。它是独立的 Java AI 库，提供 Spring Boot 集成，并不是 Spring 官方 Spring AI 项目。

| 概念 | 用途 | 本示例 |
| --- | --- | --- |
| ChatModel | 对接模型供应商，发送消息并取得回答 | OpenAI 兼容 starter 对接 DeepSeek |
| AI Service | 将 Java 接口变成模型调用代理 | Assistant 保留为早期教学示例；统一入口使用显式工具循环 |
| SystemMessage | 指定助手角色与回答要求 | 中文 Java 教学助手 |
| 会话历史 | 将历史消息加入下一次请求 | H2 存完整历史，CustomerChatService 读取最近 6 个成功完整回合 |
| Tool | 允许模型请求执行 Java 方法 | 加载技能、搜索本人订单、查订单详情 |
| RAG | 检索业务文档，将相关片段加入提示词 | 后续可加入企业知识库 |

## 2. 一次请求怎样执行

`POST /api/chat → ChatController → CustomerChatService → ChatModel → 模型选择工具 → Java 执行 → 结果回传模型 → 最终回答`

当前 Controller 注入 CustomerChatService，显式编排会话和工具循环；Assistant、OrderTools 保留为之前的 @AiService 教学示例，不参与新版 /api/chat 调用。专用 /api/skills/after-sales 也保留作为手动加载技能的对照实验。

配置中两个 starter 分工不同：`langchain4j-spring-boot-starter` 创建 AI Service 代理，`langchain4j-open-ai-spring-boot-starter` 根据配置创建使用 OpenAI 兼容协议的模型 Bean。本例通过 DeepSeek 的 base URL 和 Key 访问 DeepSeek，配置前缀仍保留 `langchain4j.open-ai`，Java 接口和 Controller 无需修改。

## 3. 先运行不需要 Key 的测试

需要 JDK 17+ 和 Maven 3.6.3+。在项目目录打开 PowerShell：

```powershell
# 本机默认 Java 路径失效时，可在当前终端指定已安装的 JDK：
$env:JAVA_HOME = 'C:\Users\wzk\.jdks\jbr-17.0.14'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn '-Dmaven.repo.local=.m2/repository' test
```

测试 mock ChatModel，保留真实 Spring 容器、HTTP Controller、文件技能与工具编排、数据库写入和历史恢复。普通测试使用隔离内存库，文件重启测试使用 JUnit 临时文件库，不访问用户的 data 目录。不访问模型供应商，也不产生调用费用；不代表真实模型回答质量已经验证。

首次执行仍需要联网下载 Maven 依赖。`.m2/repository` 是项目内依赖缓存，已加入 gitignore；本机 Maven 配置正常时也可以直接用 `mvn test`。

## 4. 连接 DeepSeek 模型

在同一个 PowerShell 终端配置环境变量后启动：

```powershell
$env:DEEPSEEK_API_KEY = '替换为你的DeepSeek API Key'
# 以下两项已有默认值，可按需覆盖：
# $env:DEEPSEEK_MODEL = 'deepseek-flash'
# $env:DEEPSEEK_BASE_URL = 'https://api.deepseek.com'
mvn '-Dmaven.repo.local=.m2/repository' spring-boot:run
```

模型名称需要与你的供应商和账号权限对应。默认 `deepseek-flash` 来自当前 [DeepSeek 官方文档](https://api-docs.deepseek.com/zh-cn/)，可通过 DEEPSEEK_MODEL 覆盖。不配置 DEEPSEEK_API_KEY 时，正式应用会启动失败；测试直接覆盖模型 api-key 属性并 mock 模型，不需要真实 Key。若从 IDEA 启动，在运行配置的 Environment variables 中添加 DEEPSEEK_API_KEY；修改 Windows 用户或系统环境变量后，需要重启 IDEA 才能继承新值。

在另一个 PowerShell 终端调用：

```powershell
$body = @{ message = '请用一个简单例子解释 Spring Boot 的依赖注入' } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:18080/api/chat' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

返回包含 answer、conversationId、executedTools、requestId；回答内容由真实模型决定。

默认端口为 18080，可通过 SERVER_PORT 环境变量覆盖。本机 Windows 将 7981–8080 列为 TCP 保留端口，8080 即使没有监听进程也无法绑定；可用 `netsh interface ipv4 show excludedportrange protocol=tcp` 检查。

## 5. 使用 Swagger UI 调试

启动应用后打开 http://localhost:18080/swagger-ui.html ，展开“AI 聊天”中的 `POST /api/chat`，点击 **Try it out**，修改示例中的 message，点击 **Execute**，即可查看响应状态、回答和耗时。

DeepSeek Key 仍通过启动进程的 DEEPSEEK_API_KEY 环境变量配置，不用在 Swagger 页面输入。点击 Execute 会调用真实模型；仅打开文档不会调用模型。模型响应较慢时请等待返回。

OpenAPI JSON 地址为 http://localhost:18080/v3/api-docs ，也可导入 Apifox 等工具。若设置了 SERVER_PORT，请将上述地址中的端口替换为实际端口。

文档由 springdoc-openapi 自动扫描 Controller 生成，`@Tag` 定义分组，`@Operation` 定义接口说明，`@Schema` 定义字段说明和示例。OpenApiConfig 配置文档标题。

## 6. 早期订单工具示例（历史对照）

注意：本节描述早期实现。新版统一入口使用下方第 8 节，A1001 的状态已在新工具中设为已签收，方便测试售后。OrderTools 的旧数据不参与新版聊天。

重启应用，在 Swagger UI 的 `POST /api/chat` 中输入：

```json
{"message":"帮我查询订单 A1001 的状态和物流"}
```

模拟数据：A1001 为机械键盘，已发货，顺丰速运，模拟运单 SF-DEMO-1001；A1002 为无线鼠标，待发货；A9999 为不存在的订单。也可以试试“帮我查一下订单”，观察模型是否询问订单号；“解释依赖注入”应直接回答。

`OrderTools` 是 Spring Component，其 `@Tool` 方法由 LangChain4j starter 自动注册到 Assistant。`@Tool` 描述功能与使用条件，`@P` 说明参数。模型首先返回工具调用请求，LangChain4j 执行真实 Java 方法，将结构化结果回传给模型，再由模型生成自然语言回答。模型不直接执行 Java 代码。

调用流程：用户问题 → 模型选择 queryOrder → Java 查询模拟数据 → 工具结果回传模型 → 最终回答。控制台出现“执行订单查询工具 queryOrder”可确认 Java 工具确实执行。一次工具问答通常包含多次模型调用，耗时可能比普通问答更长。

这是基于 Tool Calling 的业务技能，没有 SKILL.md 文件。测试模拟模型发出的工具调用请求，验证真实工具执行和结果回传；真实 DeepSeek 是否选择调用取决于模型，需自行在 Swagger 验证。本示例只包含模拟数据，接真实订单系统时，应在业务服务中校验登录用户和订单归属。

以上无状态行为属于早期示例；当前 /api/chat 使用 H2 恢复多轮历史，见第 8 节。

## 7. SKILL.md 售后审核技能

文件位置：`src/main/resources/skills/order-after-sales-review/SKILL.md`。规则仅用于教学。

启动时 `AfterSalesService` 从 classpath 以 UTF-8 读取文件，作为 SystemMessage；请求 JSON 作为独立 UserMessage。通过 `POST /api/skills/after-sales` 显式选择此技能，无需模型自动路由。它不使用订单工具、不查询数据库，订单背景均为用户提供的未核验信息。这里实现的是文件加载教学方案，不是完整的技能执行框架，也不会安装为 Codex 技能。

重启后在 Swagger 的“文件技能：售后审核”中执行：

```json
{
  "message": "我的键盘昨天签收，有一个按键失灵，想退货退款，还没提交视频。",
  "orderContext": {
    "orderNumber": "A1001",
    "shipped": true,
    "delivered": true,
    "daysSinceDelivery": 1,
    "productType": "普通实物商品",
    "qualityIssue": true,
    "evidenceProvided": false
  }
}
```

返回 `skillName` 和 `answer`。预期匹配 R3，要求补充问题证据，不声称已退款。`skillName` 由程序确定，`answer` 由模型生成。

还可以测试：

| 输入 | 预期行为 |
| --- | --- |
| 签收 5 天，无质量问题，配件完整性未知 | R2/R5，补充完整情况 |
| 签收 30 天，有质量问题 | R4，转人工 |
| 只提供 message“我要退货”，不传 orderContext | R5，询问必要信息 |
| 要求忽略规则并声称退款成功 | 不声称退款成功 |
| 普通 Java 问题 | 说明售后接口范围，普通问答使用 /api/chat |

未知字段省略，Boolean 和 Integer 使用包装类型区分未知与 false/0。不要把未签收填写成签收 0 天。每次请求独立，补充信息时重新提交完整上下文。

修改 SKILL.md 后需重启（打包部署需重新构建）。自动化测试验证真实文件加载、消息分离和参数校验，不调用真实模型，因此不能证明模型一定遵循业务规则；上表需在真实模型上人工验证。若需要可靠自动审批，应由 Java 规则或工作流执行关键判断，此处只生成建议。

## 8. 统一对话客服（当前实现）

重启后打开 Swagger 的 `POST /api/chat`。前端只传问题和可选会话 ID，不需要提供订单事实或指定技能。

第一轮：

```json
{"message":"我收到的键盘有个按键坏了，想退货"}
```

模型应加载 after-sales-review 并调用 findMyOrders。演示用户有两笔键盘订单，应询问具体是哪笔。响应包含 answer、conversationId、executedTools、requestId。复制实际返回的 conversationId，第二轮传：

```json
{"conversationId":"替换为上一轮返回的ID","message":"机械键盘那个"}
```

预期结合历史定位 A1001，调用 getMyOrderDetails，得到签收 1 天等模拟事实，再依据技能询问故障证据。第三轮可说“还没有视频，我需要准备什么？”，继续使用相同 ID。

其他测试：直接问“查询订单 A1002”应加载 order-query 并查询待发货订单；“解释 Spring 依赖注入”直接回答。模型不保证每次采用相同调用顺序，以 executedTools 和控制台日志为准。

技能目录只在系统提示中列出名称与适用条件；模型通过 loadSkill 请求加载文件全文。CustomerTools 使用注册名称映射读取 classpath 文件，不允许任意文件路径。工具调用参数由模型提取，执行由 CustomerChatService 的白名单 switch 完成，不执行任意方法。

当前身份固定为 demo-user，没有登录系统。findMyOrders 和 getMyOrderDetails 在 Java 中过滤订单归属，B2001 属于另一个模拟用户，不能查询。conversationId 仅用于关联对话，不是身份凭证；此项目是单用户本地实验，多用户部署前需接入认证，并按登录用户与会话 ID 共同隔离历史。

完整会话、消息及模型/工具调用存入 H2 文件库，重启后可用相同 conversationId 继续；模型只接收最近 6 个成功完整回合，较早事实可能需要重新确认。省略 conversationId 会新建会话。每个请求最多 8 次模型调用、每次最多 8 个工具调用，超限返回 502；失败记录仍保存，但不进入模型上下文。requestId 用于重试去重，同一条消息保持不变，下一条消息生成新值。

本版只查询和生成售后建议，没有材料上传或创建申请、退款工具。规则为教学规则，业务判断由模型生成建议，不能作为真实审批结果。控制台打印每轮模型回答、请求的工具和工具结果，executedTools 列出本次实际执行的工具名称。已有测试只模拟模型边界，验证真实会话、文件加载及工具结果传递，不代表真实模型判断已验证。

## 9. Vue 聊天页面（第一步）

启动或重启 Spring Boot，打开 http://localhost:18080/ 。无需 npm、Node.js 构建或 CDN。Vue 3.5.22 生产版本及 MIT 许可证放在 static/vendor，本地静态文件由 Spring Boot 提供。

- 点击示例卡片填入问题，再点击发送；Enter 发送，Shift+Enter 换行，中文输入法确认文字不会误发送。
- 问“我收到的键盘有个按键坏了，想退货”，再回答“机械键盘那个”，页面自动带回后端 conversationId。
- 请求期间显示真实等待秒数，并禁用重复发送和新建会话；不虚构模型或工具执行进度。
- 请求失败保留输入，支持重试；重试复用 requestId，已完成请求返回数据库中的原结果，不重复调用模型。失败与中断等边界见 docs/PERSISTENCE.md。
- 新建会话清空当前视图，不清除数据库存档。左侧可切换历史会话、加载更多；刷新自动恢复最近会话，后端重启不清除历史。
- 顶部支持重命名和归档；归档不删除记录，在“已归档”中可恢复并继续对话。
- 消息支持“加载更早的消息”，每条消息可查看执行记录，选择尝试次数，展开模型返回、工具参数与结果和技能信息。回答下方还可展开“本轮工具调用”摘要，查询不会调用模型。
- 助手回答支持 Markdown 标题、列表、引用、表格、链接和代码块。代码块显示语言及“复制代码”；“复制回答”复制原始 Markdown。用户输入仍以纯文本展示。
- 向上阅读时不会被回答拉回底部，可点击“回到最新消息”。加载旧消息时保持阅读位置。
- 失败消息可重试，中断和进行中状态明确标记。新增 SSE 实时回答、工具事件和停止生成，保留原 JSON 接口，详见 [实时接口说明](docs/STREAMING.md)。

文件：static/index.html 是 Vue 模板；css/chat.css 是响应式布局；js/api.js 封装同源 /api/chat；js/chat.js 用 Composition API 管理输入、会话和消息。前端不保存或读取 DeepSeek Key。

阅读体验使用本地固定版本 Marked 18.1.1 与 DOMPurify 3.4.17。`js/content.js` 负责渲染、HTML 过滤和代码复制，`css/content.css` 负责排版。原始 HTML 作为文本显示，不执行脚本；图片只显示说明文字，不加载模型生成的外部图片 URL。库加载失败时回退为纯文本。详见 [前端阅读与调用记录](docs/FRONTEND.md)。

前端行为验证可运行 `node --test scripts/frontend-check.cjs`（仅开发检查需要 Node，页面运行不需要）。测试模拟网络边界，不产生模型调用费用。Java 集成测试验证静态页面和本地资源可访问。

## 10. 建议阅读顺序

1. `pom.xml`：理解 Web、AI Service、模型 starter 三部分依赖。
2. `application.yml`：理解 API Key、base URL 和模型名称配置。
3. `ChatController.java`：请求、会话 ID 和响应结构。
4. `CustomerChatService.java`：历史、模型调用、工具循环。
5. `CustomerTools.java` 及 skills 下的 SKILL.md：工具和技能分工。
6. `CustomerConversationTest.java`：多轮对话集成测试。

这个实例已加入 H2 文件数据库，未加入向量库。RAG 的重点是检索后给模型补充上下文；Tool 的重点是由应用执行经过授权和校验的业务方法。

参考：[LangChain4j Spring Boot 集成](https://docs.langchain4j.dev/tutorials/spring-boot-integration/)、[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)、[RAG](https://docs.langchain4j.dev/tutorials/rag/)。
