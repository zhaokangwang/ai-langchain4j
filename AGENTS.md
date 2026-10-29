# 项目协作约束

本文件适用于整个项目。修改前先阅读相关代码、测试及 `docs/` 中对应说明，优先复用现有实现。本文约束后续改动，不代表要求立即改造所有存量代码；不得借执行规范之名扩大任务范围。

## 1. 技术栈

- 使用 Java 17、Spring Boot 3.5.7、Maven；具体依赖及版本以 `pom.xml` 为准，不使用高于 Java 17 的语言特性或 API。
- AI 集成使用 LangChain4j 1.22.0-beta32，通过 OpenAI 兼容协议访问配置的模型供应商；不自行切换为 Spring AI 或增加另一套 AI 客户端。
- HTTP 使用 Spring MVC；流式接口使用 `SseEmitter`。不为流式功能额外引入 WebFlux/Reactor。
- 数据访问使用 Spring JDBC（`JdbcTemplate` / `NamedParameterJdbcTemplate`），数据库使用 H2，结构变更由 Flyway 管理。当前没有 MyBatis、MyBatis-Plus、JPA 或 Mapper 持久层，不得按其已接入来生成代码。
- JSON 复用 Spring 管理的 Jackson `ObjectMapper`；参数校验使用 Jakarta Validation；OpenAPI 使用现有 springdoc；Excel 使用 Apache POI。
- 前端沿用 `src/main/resources/static/` 下的 Vue、现有本地 vendor 资源和原生 JavaScript；不自动增加 npm 构建链或另一套前端框架。
- Java 检查使用 Maven Checkstyle Plugin 3.6.0 + Checkstyle 12.3.1，规则位于 `config/checkstyle/`。Checkstyle 13/14 需要 JDK 21，不为升级检查工具而改变项目 Java 基线。
- Jakarta EE 相关注解使用 `jakarta.*`；不要机械替换 JDK 自带的 `javax.*` API。

## 2. 分层职责及调用方向

根包为 `com.example.ai`，启动类保留在根包。沿用现有职责包及 `report/` 业务包，不统一搬迁目录。

| 层或组件 | 负责 | 不负责 |
| --- | --- | --- |
| Controller | HTTP 路径、请求绑定、基础参数校验、状态码及响应头、响应转换；SSE 连接/心跳/取消及下载传输 | SQL、数据库事务、业务规则和多步骤业务编排 |
| Service | 业务规则、权限校验、用例编排、事务边界；聊天服务协调技能、工具、模型及持久化 | HTTP 连接生命周期；新增业务方法不返回 `ResponseEntity` 或操作 Servlet 对象 |
| Repository | 参数化 SQL、数据映射、持久化、锁及必要的原子数据库状态操作 | 调用 Controller、编排模型网络请求、决定 HTTP 响应格式 |
| tool / model / skill / security | 分别承载受控业务工具、模型适配及审计、技能注册与路由、后端身份与数据范围 | 绕过既有权限与执行范围约束 |

- 普通业务遵循 `Controller → Service → Repository → 数据库`。Controller 不直接注入 Repository、JDBC 模板、模型客户端或工具组件，不跨层写 SQL；身份与技能能力查询也通过 Service。
- Service 可以调用职责明确的其他 Service、Repository 及所需业务组件，但不形成循环依赖。共享业务能力由 HTTP 入口和工具入口共同复用。
- JDBC 和 SQL 只放在 Repository。历史查询、报表快照及执行审计分别复用 `HistoryRepository`、`OrderReportRepository`、`ExecutionAuditRepository`；Service、报表服务和模型适配器不直接访问 JDBC。
- 跨层共享请求/响应已提取到 `chat/`、`aftersales/`、`history/`、`report/` 对应业务包；Service 和 Repository 不得依赖 Controller 类型。Repository 不反向依赖 Service 或模型适配器。
- `CustomerChatService` 负责请求锁、会话与模型循环编排；`ChatToolExecutor` 负责工具参数、授权范围校验及执行；`SkillRouter` 各步骤只使用单次请求局部状态，不能把用户选择存到单例可变字段中。
- 当前 Repository 中的短事务与状态机是有意设计，不因“事务必须在 Service”而机械删除或搬迁。
- 本项目的 Repository 承担数据访问层职责。DTO 转换器与数据库 Mapper 是不同概念，不为补齐三层命名创建空 Mapper 或空 Service 接口。

## 3. 接口规范

### DTO / VO 与返回结构

- 入参与出参分开定义，沿用 `XxxRequest`、`XxxResponse` 等有业务含义的命名；简单不可变数据优先使用 Java record。不要求所有响应增加 `VO` 后缀。
- 只在单个 Controller 使用的 record 可继续内嵌；需要跨入口复用时再提取到对应业务包。不为了形式统一引入对象转换框架。
- 不将数据库内部字段、权限字段或持久化对象整体暴露给前端；请求不得直接绑定内部状态对象。
- 当前没有统一 `Result<T>` / `ApiResponse<T>`：普通 JSON 接口直接返回既有响应对象。保留现有路径、字段名、类型、ID 及状态码，不擅自套 `code/message/data` 外壳或增加 `/api/v1` 前缀。
- SSE 保持现有事件名称、载荷和终止语义；Excel 下载保持二进制正文、Content-Type 与 Content-Disposition，不参与 JSON 包装。统一返回结构属于独立迁移任务，需同时更新调用方、OpenAPI 和测试。

### 分页

- 页码从 0 开始，`size` 为 1–100；默认值及额外页码限制沿用对应接口。无效参数返回 400，不自行改成静默截断。
- 会话列表保持 `{items, page, hasMore}`，默认 `size=20`。
- 消息列表保持 `{conversation, items, hasMore, nextBeforeId}`，使用 `beforeId` 游标，默认 `size=30`；保持返回消息的时间正序。
- 报表保持 `ReportModels.Page`：使用 `rows/page/size/hasMore`，保留汇总、分组及快照链接等字段，默认 `size=10`。分页与下载基于同一快照，汇总覆盖完整结果。
- 不为了统一字段强制增加全量 COUNT，不直接返回数据库或未来 ORM 框架的分页对象。新接口优先复用同类业务的分页契约，并使用稳定排序及唯一键作为排序补充。

### 参数校验

- HTTP 请求结构校验使用 `@Valid` 及 `jakarta.validation.constraints`，包括必填、长度、格式和嵌套对象校验；Query/Path 参数也必须校验范围与格式。
- 日期先后、业务状态、对象归属、权限及组合条件由 Service/共享业务组件校验。工具调用不一定经过 Controller，不能仅靠 HTTP 校验保证业务正确。
- 沿用现有 Service 对报表条件、分页等参数的校验，不要求为本次文档改动批量迁移。
- 未知与 false/0 含义不同时，使用可空包装类型，保留已有语义。

## 4. 异常与错误处理

- 当前没有自定义统一业务异常、错误码枚举或全局 `@RestControllerAdvice`。不要引用不存在的 `BusinessException`、`ErrorCode` 或 `GlobalExceptionHandler`，也不要在不同模块各自新建一套。
- 预期请求错误沿用 `ResponseStatusException`：参数错误 400、无权限 403、资源不存在或按现有规则不可见 404、状态/并发/幂等冲突 409；模型调用轮数超限沿用 502，SSE 队列满沿用 503。
- 保留 `ReportController.inputError` 的状态码、`{message}` 正文及禁用缓存响应头。其他接口保留其当前错误契约，不宣称所有错误已统一。
- SSE 响应开始后的错误通过现有 `error` 事件返回；保留 `requestId/status/message`。其中取消状态 499 是事件载荷中的约定，不要求将已发送的 HTTP 响应改为 499。
- 不用 HTTP 200 掩盖错误，不吞掉异常后返回空成功对象。捕获异常应服务于状态落库、资源清理或明确的边界转换；意外异常保留原因和诊断信息，不向客户端暴露堆栈、SQL 或密钥。
- `ChatStream.Interrupted` 保留为流式取消/中断信号。失败、中断、重试和完成状态的持久化语义不得随意合并。
- 若以后统一异常，作为独立任务确定业务异常、错误码及唯一全局处理器，并覆盖参数绑定、JSON 解析、405 等框架错误，保留正确状态码和必要响应头；同步迁移前端、测试与 SSE 边界。

## 5. 数据访问、SQL 与事务

- 当前统一复用 Spring JDBC，不新增 MyBatis-Plus 的 `BaseMapper`、`IService`、Wrapper 或 XML。未来切换数据访问方案需单独确定迁移范围。
- SQL 值使用 `?` 或命名参数绑定。动态字段、表名、排序及 SQL 片段只能来自后端固定映射/白名单，不能接受用户或模型生成的任意 SQL。允许组合固定条件片段。
- 数据权限由 `DemoIdentity` 及现有服务端规则决定；HTTP、工具、历史重放、快照读取和下载均须保留相应范围校验，不能用前端或模型提供的 owner/role 扩权。
- 列表查询必须有分页或明确上限，不先查全表再分页。现有报表是有界完整快照：最多 10000 条，查询限额加 1 检测溢出，超限明确拒绝；不要静默截断或改为当前页汇总。
- 保留报表关键词的字面匹配转义规则，不让 `%`、`_` 意外扩大查询范围。新增查询选择所需字段，避免无界 IN、N+1 查询及无条件批量更新/删除。
- 多步骤业务写入在 Service 明确事务单元；沿用现有 `TransactionTemplate`。确需使用 `@Transactional` 时保证经 Spring 代理调用，不依赖同类自调用，并明确异常回滚范围。
- 数据库事务保持短小，不覆盖模型/HTTP 网络等待、SSE 整个生命周期或 Excel 生成。保留聊天“开始记录 → 事务外调用 → 短事务保存结果”的流程。
- 最终回答、完整回合上下文和完成状态必须保持原子提交；失败/中断记录不能被无意回滚。保留 requestId 去重、已完成结果重放和中断后禁止自动重放的行为。
- 同构批量写优先使用 JDBC `batchUpdate`，按有界批次执行；批次大小及整体原子提交或分批提交取决于业务要求，必须明确失败语义。不要用逐条远程调用或拼接超长 SQL 代替批处理。
- 启动恢复按 requestId 游标每批读取最多 500 条未完成请求并批量写审计事件；恢复事件和状态转换在同一事务内提交，重复恢复不能生成重复事件，不自动重放模型调用。
- 数据库结构变更新增 `src/main/resources/db/migration/` 下的 Flyway 迁移；不得修改已应用的迁移、删除用户数据库或以清库解决迁移冲突。

## 6. 项目边界

- 不随意升级/降级依赖，不调整 BOM 管理的版本，不添加与现有能力重复的组件。新增依赖应先说明现有组件无法满足的具体需求及影响。
- 只修改与任务有关的代码；不夹带全项目格式化、包迁移、抽象层增加或历史示例清理。`Assistant`、`OrderTools` 等保留教学用途，不自动替换当前聊天主链路。
- 保留技能名称、注册表、角色范围、工具授权及审计语义。身份和业务事实由后端核验，提示词不能替代权限控制或确定性规则；模拟建议不得被改成真实退款等操作。
- 密钥通过环境变量或现有配置机制提供；不写入源码、文档示例真实值、日志或测试。不得为运行验证默认调用收费模型接口。
- 不修改/清理用户 `data/`、Maven 缓存或 IDE 配置来规避失败。构建输出位于 `target/`，不手工编辑生成的 class 或 jar。
- 修改接口或业务行为时同步维护相关测试、OpenAPI 和对应 `docs/` 说明；发现无关问题单独报告。

## 7. 可执行验证

在项目根目录使用 PowerShell。依赖缓存沿用项目内 `.m2/repository`，首次运行可能需要联网；项目没有 Maven Wrapper，使用 `mvn`。

本机默认 Java 启动路径曾失效。若终端未配置可用 JDK，可仅在当前进程使用以下已验证路径；其他机器替换为本机 JDK 17，不修改系统全局环境：

```powershell
$env:JAVA_HOME = 'C:\Users\wzk\.jdks\jbr-17.0.14'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

```powershell
# 编译
mvn '-Dmaven.repo.local=.m2/repository' compile

# 后端测试
mvn '-Dmaven.repo.local=.m2/repository' test

# 单个相关测试；替换为实际存在的测试类
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=OrderReportIntegrationTest' test

# 单独执行 Java Checkstyle 检查
mvn '-Dmaven.repo.local=.m2/repository' checkstyle:check

# 完整构建：包含 Checkstyle、编译、测试、打包
mvn '-Dmaven.repo.local=.m2/repository' verify

# 前端行为测试：使用 Node 内置测试运行器，无需 npm install
node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs

# 应用 JavaScript 语法检查；首个失败立即退出
$sourceScripts = Get-ChildItem -LiteralPath src/main/resources/static/js -Filter '*.js' -File
foreach ($sourceScript in $sourceScripts) {
    node --check $sourceScript.FullName
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

- Checkstyle 在 Maven `validate` 阶段检查主代码和测试，违规会使 `compile/test/verify` 失败；规则及边界见 `docs/CODE-QUALITY.md`。不得以跳过检查、提高阈值或增加大范围豁免来掩盖违规。
- 所有方法圈复杂度最多 30，无存量豁免或额外上限。路由已拆分，不得恢复原来的 70 上限或增加 suppression 来掩盖复杂方法。
- 当前未配置 Spotless、PMD、SpotBugs 或完整 Java 架构检查；不得宣称这些工具已运行。Checkstyle 的 import 约束不等于完整调用关系验证，JavaScript 的 `node --check` 仅检查语法。
- 先按改动范围执行相关验证；影响后端行为时运行相关测试，交付后端代码前运行 `verify`；涉及前端或接口契约时运行前端行为测试及相关语法检查。纯文档改动检查准确性与引用，无需机械重复全套测试。
- 测试使用隔离内存库或 JUnit 临时文件库，不接触用户 `data/`。模型边界使用 mock 或本地模拟供应商；自动化通过不代表真实模型回答质量已验证。
- 报告实际执行命令及结果。环境/网络导致失败时说明原因，不通过跳过测试、删除断言或改用真实密钥掩盖问题。
- 验证基线（2026-10-29）：JDK 17.0.14、Maven 3.9.9、Node 22.18.0；职责分层重构后 `mvn verify` 成功，0 个 Checkstyle 违规，59 个后端测试、20 个前端行为测试及应用 JavaScript 语法检查通过。

## 参考

- 项目事实以 `pom.xml`、实际代码和测试为准；配套说明见 `docs/PROJECT-STRUCTURE.md`、`docs/PERSISTENCE.md`、`docs/STREAMING.md`、`docs/REPORTS.md` 及 `docs/SKILL-AUTHORIZATION.md`。
- 编写结构参考 [spring-boot-skills 的 Boot 3 AGENTS 模板](https://github.com/rrezartprebreza/spring-boot-skills/blob/main/templates/spring-boot-3/AGENTS.md) 及其分层、接口和事务约定，已按本项目调整，不代表安装或引入该仓库的技能及依赖。
