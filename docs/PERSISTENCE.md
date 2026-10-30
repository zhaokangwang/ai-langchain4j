# 文件数据库：记录与上下文恢复

## 本阶段范围

已实现 H2 文件存储、Flyway 版本迁移、会话恢复、请求去重、模型/工具调用记录和启动中断恢复。
记录从本次版本开始产生，之前只存在内存或页面里的历史不能自动找回。
统一 `/api/chat` 和旧 `/api/skills/after-sales` 接口均记录模型调用。旧接口仍为每次独立咨询，不提供请求去重参数。
第三阶段已加入历史会话列表、分页消息、执行记录查询、重命名、归档及恢复。归档不删除数据。清理和导出尚未添加。

## 文件位置与启动

默认 JDBC URL 为 `jdbc:h2:file:./data/customer-service;DB_CLOSE_ON_EXIT=FALSE`。
从项目根目录启动后，会在 `data/customer-service.mv.db` 持久化。该路径相对于进程工作目录，IDEA 应将 Working directory 设为项目根目录。

可在启动前设置绝对路径（不包含 `.mv.db` 后缀）：

```powershell
$env:CHAT_DB_PATH = 'D:/coding/idea/ai-langchain4j/data/customer-service'
```

可用 CHAT_DB_PASSWORD 设置数据库密码；首次创建后更改环境变量不会自动更改已有库的密码。默认无密码，仅用于本机实验。应用未开启 H2 Web Console 或 TCP 服务器。
一个文件库由一个 Spring Boot 进程打开，停止旧实例后再启动新实例；不要启动两个后端共享此文件。
Flyway 从 `db/migration` 执行建表迁移，不使用启动清表。后续结构变更新增 V2 等迁移，不修改已经应用的 V1。
data/ 与数据库文件已忽略，不会提交到 Git。可在后端完全停止后备份 data 目录，不要直接复制运行中的数据库文件。

## 记录内容

| 表 | 内容 |
| --- | --- |
| user_accounts | V6 新增账号、密码哈希、角色及管理/启用/待改密标记；支持 Session 登录、本人改密及第三批账号管理 |
| conversations | 角色共享分区、标题、入口来源、创建/更新时间、状态 |
| chat_runs | 一条逻辑请求、requestId、原始输入、状态、重试次数、最终响应、完整回合上下文 |
| chat_messages | 用户输入、最终助手回答；失败请求也保留用户输入，通过关联 chat_runs 查看状态 |
| model_calls | 每次模型调用的输入消息与工具定义、AI 返回消息、模型名、Token 用量、状态与耗时 |
| tool_calls | 调用 ID、工具名、参数、结果、技能名称和 SHA-256 内容摘要、状态与耗时 |
| operation_events | 新会话首条请求、发送、重试、完成、失败、返回缓存和启动中断恢复事件 |

model_calls.request_json 内 messages、tools 使用 LangChain4j 的 JSON 编码字符串，便于保留类型和完整工具协议。
技能全文保存在工具结果及模型输入快照，SHA-256 标识本次实际使用的文件内容。
Token 用量只有供应商返回时才保存，否则为 NULL，不能视为 0。
不读取或保存 API Key、Authorization 请求头或传输配置；错误只保存异常类型，不保存可能包含内部信息的异常正文。用户消息及工具结果会完整入库，请自行管理这些业务数据。
本阶段不跟踪键盘输入、滚动、浏览器本地清空等未发送给后端的操作，也不记录 Spring MVC 校验前被拒绝的 HTTP 请求；不是操作系统审计日志。

## 重启后继续对话

首次发送：

```json
{"requestId":"demo-r1","message":"键盘坏了想退货"}
```

记下响应中的 conversationId。重启后端，使用同一 ID 和新的 requestId：

```json
{"requestId":"demo-r2","conversationId":"替换为之前返回的ID","message":"机械键盘那个"}
```

系统会从文件库恢复最近 6 个成功完整回合（包括工具请求、调用 ID 与工具结果）。完整历史不自动删除；模型上下文窗口仅取最近 6 回合。超出窗口的事实需要重新确认。
新建会话只切换到新 ID，不清除旧记录。前端左侧可选择历史会话，刷新时自动恢复最近打开的一项。浏览器只保存最近会话 ID，消息内容重新从数据库读取。
后端重启后会话不再因 30 分钟或 100 个内存会话限制而消失。
状态和模型记录以数据库为准；浏览器响应丢失时记录仍可能已经完成。

## 请求重试约定

- requestId 可省略，服务端生成并随成功响应返回；要可靠处理响应丢失，客户端应提前生成。
- 页面已为每条新消息自动生成 requestId，失败重试沿用同一个 ID。
- COMPLETED：相同 ID、输入和会话返回原保存结果，不再次调用模型；记录缓存返回事件。
- FAILED：同一 ID 的显式重试可开启新尝试，不重复插入用户消息；之前模型/工具调用仍按 attempt_no 保留。
- RUNNING：拒绝并返回 409，避免重复执行。
- INTERRUPTED：拒绝自动重放，返回 409。确认后可使用新 requestId 重新发起。原记录继续保留。
- 同一 ID 换消息、换会话或换接口返回 409。
- 同一会话的并发发送返回 409；采用固定分段锁，少量不同会话并发也可能因锁冲突而返回 409，可稍后重试。

启动时将上次进程遗留的 RUNNING 请求、模型调用和工具调用转为 INTERRUPTED。
失败/中断回合不加入模型上下文；若早先失败的请求后来重试成功，按成功完成顺序恢复回合。
等待模型期间不持有数据库事务。最终回答、完整回合和请求完成状态在同一个短事务中提交。
这保证应用内已完成结果的复用，不保证模型供应商恰好执行一次：网络超时、进程在远端执行与本地落库之间崩溃等情况下，供应商可能已处理，但本地没有完整结果。失败重试可能再次产生模型费用。

## 本地查看记录

可以在聊天页面选择会话，再点击消息下的“查看执行记录”，查看每轮模型返回、工具参数与结果、技能内容摘要及失败重试情况。页面的历史列表只展示统一 /api/chat 创建的当前演示用户会话；旧售后专用接口记录仍保存在数据库，可用 SQL 查看。

若使用支持 H2 的数据库工具，先停止后端，再连接同一文件 URL（用户 sa，密码与启动配置一致）。
也可以在项目目录使用 H2 Shell（需要 Java 17；依赖版本由当前 pom 管理）：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -cp '.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar' org.h2.tools.Shell -url 'jdbc:h2:file:./data/customer-service' -user sa
```

按提示输入密码。只查看记录可执行：

```sql
SELECT id, title, source, created_at, updated_at FROM conversations ORDER BY updated_at DESC;
SELECT request_id, conversation_id, status, attempt_count, error_type, duration_ms FROM chat_runs ORDER BY run_seq DESC;
SELECT role, content FROM chat_messages WHERE request_id = 'demo-r1' ORDER BY id;
SELECT attempt_no, round_no, model_name, status, total_tokens, duration_ms, response_json FROM model_calls WHERE request_id = 'demo-r1' ORDER BY attempt_no, round_no;
SELECT tool_name, arguments_json, result_text, skill_name, skill_sha256, status FROM tool_calls WHERE request_id = 'demo-r1' ORDER BY started_at;
SELECT event_type, details, created_at FROM operation_events WHERE request_id = 'demo-r1' ORDER BY id;
```

时间列为 Unix 毫秒。读取 message 或模型输入不意味着再次调用模型。

## 验证

`mvn '-Dmaven.repo.local=.m2/repository' test`：普通测试使用隔离内存库；FileDatabaseRestartTest 使用 JUnit 临时文件库，真正关闭连接池再打开，验证迁移不清表、完整工具协议恢复和缓存回答复用，不写入用户 data/。
ChatRecordingTest 覆盖失败记录/重试、请求冲突、并发控制、中断恢复、上下文窗口、Token 信息与凭据隔离。
`node --test scripts/frontend-check.cjs`：验证前端连续会话及重试复用 requestId，不需要真实模型 Key。

账号第四批已接入登录角色与共享业务数据。会话、执行记录和报表按 demo-user/demo-service/demo-warehouse 三个角色分区共享，不按账号隔离；新 AUTHORIZATION 审计另记实际账号 actor。旧分区 ID 和数据库迁移保留，详见 [ROLE-SHARING.md](ROLE-SHARING.md)。登录状态不持久化到 H2，重启后需要重新登录。

## 第三阶段接口

| 接口 | 行为 |
| --- | --- |
| GET /api/conversations?status=ACTIVE&page=0&size=20 | 最近会话；status=ARCHIVED 查询归档，返回 hasMore |
| GET /api/conversations/{id}/messages?size=30 | 最近消息，按正序返回；使用 nextBeforeId 作为 beforeId 加载更早记录 |
| PATCH /api/conversations/{id} | 传 title 重命名，或 status=ARCHIVED/ACTIVE 归档/恢复，记录操作事件 |
| GET /api/chat-runs/{requestId}?attempt=1 | 默认最后一次尝试，可选历史尝试；包含模型与工具，操作事件最多最近 100 条 |

查询仅返回当前角色分区的 CHAT 来源，其他角色或其他来源的记录返回 404；同角色不同账号可以共享记录。会话分页 size 最多 100，消息分页 size 最多 100。尝试详情一次只加载一轮尝试的调用记录，避免所有重试内容一起返回。
归档后可读取记录，但不能发送新消息或重试失败请求。可恢复后继续对话。RUNNING 请求存在时更改归档状态返回 409；完成结果的幂等重放仍允许，因为不执行新任务。
页面能区分失败、中断和进行中状态，失败消息可显式重试；中断消息不会自动重放。另一个标签页中尚在执行的请求可通过“刷新状态”查看，不进行假进度展示。
重命名与归档事件属于会话级事件，保存在 operation_events；单条请求详情中的事件按 requestId 展示，不包含无 requestId 的会话级事件。

V2 迁移只增加查询索引；V3 增加 chat_runs.business_state_json 保存每次成功回答的业务状态，不清空历史。文件测试覆盖 V1 旧记录升级到 V3，以及当前版本关闭、重开后的状态和完整审计恢复。旧记录没有业务状态时按空状态处理，不猜测业务对象。

多角色技能第二阶段会按当前问题及业务状态筛选候选。最近六个成功回合的完整工具协议继续留在审计中，模型实际请求仅携带历史用户消息和最终助手回答，技能正文按需加载。状态与回答原子保存，失败或取消不更新状态，详见 [SKILL-ROUTING.md](SKILL-ROUTING.md)。
