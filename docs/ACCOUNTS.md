# 账号登录：分批实施

## 约定与进度

业务角色仅使用现有 `buyer`、`service`、`warehouse`，对应 `DemoIdentity.Role` 的 BUYER、CUSTOMER_SERVICE、WAREHOUSE。管理员使用独立管理标记，不增加第四种业务角色。业务数据按角色共享，不做账号级隔离。

当前完成第一至第四批：账号基础、Session 登录与本人改密、管理员账号管理，以及登录角色对应的 Skill/工具授权、角色共享历史和报表、SSE 身份快照与账号有效性复核。

当前业务身份来自已认证账号，`app.demo.account` / `DEMO_ACCOUNT` 不再决定业务角色。同角色账号共享会话、执行记录及报表，不同角色按现有业务范围授权并隔离会话/快照；管理员标记不放大业务角色权限。兼容分区 ID、异步处理和复核边界见 [ROLE-SHARING.md](ROLE-SHARING.md)。

第五批已完成真实 HTTP 全流程联调及页面契约检查，修复表单 pattern 和 OpenAPI 状态码声明。修复后专项和完整构建均通过：106 个后端测试、41 项前端测试及 9 个应用脚本语法检查通过，Checkstyle 0 违规，jar 已生成。实际浏览器点击与视觉验收因工具环境故障未执行。详见 [第五批交付记录](ACCOUNT-DELIVERY.md)。

## 数据与初始化

新增 Flyway `V6__user_accounts.sql` 创建 `user_accounts`，包含账号 ID、账号名、密码哈希、业务角色、管理员标记、启用状态、待改密标记、Session 版本和创建/更新时间。时间沿用 Unix 毫秒；账号名唯一、小写、无首尾空格，长度 3–64；角色由数据库约束限制为三个固定值。已有 V1–V5 不修改，也不删除现有历史或报表数据。

应用的 `ApplicationRunner` 在 Flyway 完成后初始化 `admin`：

- 初始密码默认 `admin`；首次创建前可通过 `APP_ADMIN_PASSWORD`（对应 `app.accounts.initial-admin-password`）覆盖。
- 仅存 BCrypt 加盐哈希，不存明文。第一批引入 `spring-security-crypto`；第二批改用包含该组件的 `spring-boot-starter-security`，复用框架的 Session 安全上下文和 CSRF 防护，避免手写同类 HTTP 安全基础设施。版本仍由现有 Spring Boot BOM 管理，未额外保留重复的 crypto 直接依赖。
- 默认关联 `buyer` 业务角色，管理员标记为 true，启用状态为 true，待改密标记为 true，Session 版本为 0。第二批已接入待改密限制与 Session 版本校验。
- 初始密码至少 4 个字符且不可全为空白，UTF-8 编码最多 72 字节，避免 BCrypt 的输入长度限制。测试演示默认密码 `admin` 满足此条件。
- 已存在 `admin` 时，不覆盖其密码、角色、启用状态、待改密标记、Session 版本或时间；修改环境变量不能用于重置已有密码。
- 账号唯一约束阻止重复创建；遇到并发初始化只复用已创建的管理员。如果已有同名普通账号，启动明确失败，不自动提升权限。

`AccountService` 负责初始化规则和查询账号名的去空白、小写归一化，`AccountRepository` 负责参数化 SQL。初始化只有一次原子 INSERT，不需要额外多步骤事务。`UserAccount` 是内部记录，其 `toString()` 不包含密码哈希；后续 HTTP 接口必须另建响应类型，不直接返回该记录。

第一批提供内部账号查询与启动初始化，第二批增加本人修改密码；第三批提供普通账号创建、分页列表、管理员重置密码和禁用/启用，并落实管理员权限与预设管理员保护。三个入口使用同一 `AccountPasswordPolicy` 校验密码边界；本批不新增数据库字段或修改已有迁移。

## 第二批使用方法

启动应用后访问 `/` 或 `/reports.html`，未登录会跳到 `/login.html`。首次使用默认 `admin/admin`（或初始化前设置的密码）登录，进入 `/account.html` 修改初始密码；成功后重新登录，再进入工作台。

聊天和报表页顶部显示登录账号和账号角色，提供“修改密码”和“退出登录”。业务能力面板显示同一登录角色的技能与共享分区；不提供请求端角色切换。

- Session 使用 HttpOnly、SameSite=Lax 的 Cookie，禁用 URL 重写跟踪，空闲 30 分钟过期；Session 不持久化到数据库，后端重启需要重新登录。HTTPS 部署应让容器正确识别 HTTPS，并可通过 `server.servlet.session.cookie.secure` 配置 Secure Cookie。
- 登录成功废弃登录前的 Session 并创建新的安全上下文，防止沿用固定 Session ID；登录后必须重新获取 CSRF 令牌。
- 每次新 HTTP 请求检查账号是否存在、启用且 Session 版本一致。禁用或版本变化会清除当前会话；重新启用不会恢复已失效的会话。
- 退出仅注销当前 Session；本人改密校验当前密码并通过单条条件 UPDATE 原子更新密码哈希、清除待改密标记和递增 Session 版本，所有旧登录在下次请求时失效。哈希计算不占用数据库事务。
- 待改密账号只允许访问认证接口、改密页面和本地静态资源。访问业务页重定向到改密页；业务 API 返回 403 和 `{message,passwordChangeRequired:true}`。
- CSRF 防护覆盖登录、退出、改密、聊天、流式请求/取消、会话修改和报表创建。所有写请求先取得令牌再发起，前端收到 401 返回登录页；普通 403 清除令牌缓存并显示错误，不自动重发业务写请求。
- 请求校验和 JSON 解析错误在认证 Controller 内转换为通用 400 消息，避免默认异常日志输出密码的 rejectedValue。其他模块保持已有错误契约。
- 第四批已为 SSE 捕获独立身份快照。模型等待期间约每秒复核账号，工具执行和最终提交前再次校验；禁用、版本失效或角色变化时中断生成。取消仍为尽力执行，已完成的操作和已发出的片段不会撤回，详见角色共享说明。

第二批未提供账号管理，第三批已补齐。当前仍不开放注册、密码找回或一个账号多角色选择。

## 第三批：管理员账号管理

管理员登录并完成初始改密后，可从聊天或报表页顶部“账号管理”进入 `/admin-accounts.html`。普通账号看不到入口，直接访问页面或 API 也会得到 403；未登录的 API 返回 401。管理标记由服务端账号记录决定，不能通过请求头、角色参数或创建账号请求授予管理员权限。

页面提供以下功能：

- 查询：按账号关键词、业务角色、启用状态组合筛选，默认每页 20 条，提供上一页/下一页。查询按创建时间及唯一 ID 倒序稳定排序，数据库只读取当前页加一条判断 hasMore，不执行全量 COUNT。关键词忽略大小写，`%`、`_`、`!` 按字面匹配。
- 添加：填写账号、初始密码及确认密码，选择 buyer/service/warehouse 之一。账号允许 3–64 位字母、数字、点、下划线和短横线，以字母或数字开头，存储时统一小写。创建后默认启用、普通管理权限、下次登录须改密；账号重复返回 409。
- 重置密码：管理员指定新密码，不需要目标账号原密码，也不读取或展示旧密码。成功后递增 Session 版本，所有旧登录在下次请求失效，下次登录须改密。重置禁用账号不会自动启用它；重置自己时页面立即返回登录页。
- 禁用/启用：操作前确认，状态实际变化时递增 Session 版本。重新启用不会恢复旧登录；重复提交相同状态是幂等操作，不额外改变版本或更新时间。
- 保护：禁止禁用预设 admin 和当前操作账号；不提供删除、修改账号名、修改已有账号角色或创建其他管理员的操作。

页面使用本地 Vue 和原生 dialog，保存期间阻止重复提交和关闭弹窗；提交失败显示服务端提示，保留账号及角色并清除输入密码，关闭或提交成功也会清除密码字段。密码及密码哈希不写入浏览器存储、不回显到列表。

| 管理接口 | 请求与响应 |
| --- | --- |
| GET /api/admin/accounts?keyword=&role=&enabled=&page=0&size=20 | 返回 `{items,page,size,hasMore}`；enabled 不筛选时应省略；size 1–100、page 0–100000 |
| POST /api/admin/accounts | `{username,password,role}`；201 返回账号摘要，服务端固定普通账号和启用状态 |
| POST /api/admin/accounts/{id}/password | `{newPassword}`；成功返回 204 |
| PATCH /api/admin/accounts/{id}/status | `{enabled:true/false}`；成功返回账号摘要；缺少 enabled 返回 400 |

账号摘要仅包含 `id/username/role/administrator/enabled/passwordChangeRequired/createdAt/updatedAt`，不包含密码哈希、Session 版本或其他内部凭据。写接口均需 CSRF。无效条件或字段返回 400，未知账号返回 404，重名及禁止禁用返回 409。

Security 过滤器每次请求从数据库刷新管理员能力，页面和 `/api/admin/**` 都要求 ACCOUNT_ADMIN；Service 再次检查当前账号、版本、管理员标记及待改密状态，工具等非 HTTP 入口也不能绕过。密码哈希计算在事务外；创建采用唯一约束，重置及状态操作在 Service 的短 `TransactionTemplate` 中锁定目标账号后更新。Repository 只负责参数化 SQL。校验异常在管理 Controller 局部转换，避免密码 rejectedValue 被默认日志记录。

第三批不增加新依赖，也不清理已有账号、聊天或报表数据。第四批已完成 Skill 与登录角色及共享数据分区的绑定。

## 认证接口

| 接口 | 行为 |
| --- | --- |
| GET /api/auth/csrf | 匿名可用，返回 `{headerName,token}`；将 token 放在该 headerName 指定的请求头中 |
| POST /api/auth/login | `{username,password}`，成功创建 Session，返回当前账号；错误或禁用统一 401 |
| GET /api/auth/me | 返回 `{id,username,role,administrator,passwordChangeRequired}`，不含哈希和 Session 版本 |
| POST /api/auth/logout | 注销当前 Session，返回 204 |
| POST /api/auth/password | `{currentPassword,newPassword}`，成功后返回 204，必须重新登录 |

认证写请求均需要 CSRF，包括匿名登录。未登录的业务 API 返回 401，页面重定向登录页；已登录但令牌缺失/无效返回 403，匿名登录缺少令牌也返回 403。密码为 4 字符至 72 UTF-8 字节，新密码不可与原密码相同。失败的改密不改变密码和 Session 版本；并发状态变化导致条件更新失败时返回 409。

本地文档和 `/v3/api-docs` 也要求登录并完成初始改密。浏览器通过登录页登录后会自动携带 Cookie；Swagger 中先执行 `/api/auth/csrf`，在 Authorize 的 `csrfToken` 中填写返回的 token，再执行写请求。登录或退出后原 token 失效。模型 API Key 仍只配置在后端；执行聊天会产生模型调用。

前端新增 `auth.js` 管理同源请求与令牌，`auth-page.js` 管理登录/改密交互，`account-bar.js` 管理业务页面的账号展示和退出。密码、哈希和 Session 凭证不保存到 localStorage；登录、退出和改密清除旧的最近会话 ID。跳转目标仅接受固定的本地业务页面，拒绝外部地址。

### PowerShell 调用

先通过页面完成初始改密。下面的账号密码在交互窗口输入，不写进命令历史；PowerShell 创建独立于浏览器的 Session。示例仅调用认证接口，不调用模型。

```powershell
$loginCredential = Get-Credential -Message '输入已完成初始改密的账号'
$csrf = Invoke-RestMethod -Uri 'http://localhost:18080/api/auth/csrf' -SessionVariable loginSession
$loginBody = @{username=$loginCredential.UserName;password=$loginCredential.GetNetworkCredential().Password} | ConvertTo-Json
$loginAccount = Invoke-RestMethod -Uri 'http://localhost:18080/api/auth/login' -Method Post -WebSession $loginSession -Headers @{$csrf.headerName=$csrf.token} -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody))
Remove-Variable loginBody,loginCredential
# 登录已轮换 Session，必须重新取得令牌；后续业务请求复用这两个变量。
$csrf = Invoke-RestMethod -Uri 'http://localhost:18080/api/auth/csrf' -WebSession $loginSession
```

## 验证

```powershell
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=AccountFoundationTest,AccountBootstrapIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=AuthenticationIntegrationTest,ChatIntegrationTest,StreamingChatIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=AccountAdministrationIntegrationTest,AuthenticationIntegrationTest,AccountFoundationTest' test
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=RoleAccessIntegrationTest,RoleStreamingIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=AccountWorkflowHttpIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' verify
node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs scripts/auth-frontend-check.cjs scripts/admin-accounts-frontend-check.cjs scripts/page-contract-check.cjs
```

`AccountFoundationTest` 使用隔离内存库及 JUnit 临时文件库，覆盖密码加盐与校验、三个角色读写、文件库重启不覆盖账号、V5 升级保留历史/订单、密码 UTF-8 字节上限、数据库唯一/角色约束、同名普通账号不提权和并发插入冲突。

`AccountBootstrapIntegrationTest` 使用真实 Spring 容器及隔离内存库，验证迁移后管理员自动初始化、演示身份保持不变和登录后的能力查询。既有业务 HTTP 测试通过显式导入 `TestAuthenticatedRequests` 使用真实存在的测试账号和 CSRF，未关闭安全过滤器。独立的 `AuthenticationIntegrationTest` 不导入该配置，覆盖匿名拒绝、登录、Session 轮换、CSRF、改密、所有旧会话失效、禁用/版本变化、原子更新及校验错误的凭据隔离。模型使用 mock，不访问真实模型，不接触用户 `data/`。

`AccountAdministrationIntegrationTest` 新增 12 个测试，覆盖 HTTP/Service 管理权限、三个角色创建及不可伪造管理员字段、重名、分页和字面查询、密码重置、禁用再启用不恢复旧登录、预设管理员及自我保护、幂等状态、无效参数、权限撤销、CSRF 和密码日志隔离。`admin-accounts-frontend-check.cjs` 新增 9 个前端测试，覆盖筛选分页、创建/重置、重复提交、错误提示、状态确认、自我保护、权限撤销与管理入口显示。

第一批验证记录（2026-10-29）：JDK 17.0.14、Maven 3.9.9，账号基础专项测试 9 个通过；完整 `verify` 成功，68 个后端测试通过、0 失败/错误/跳过，Checkstyle 为 0 违规，完成打包。当时未修改前端或 HTTP 契约，未重复运行前端测试。

第二批验证记录（2026-10-29）：登录专项测试 10 个通过；完整 `mvn '-Dmaven.repo.local=.m2/repository' verify` 成功，78 个后端测试通过、0 失败/错误/跳过，Checkstyle 为 0 违规。前端三份行为测试共 28 个通过，`static/js` 全部脚本通过 `node --check`。浏览器自动化因工具环境无法启动而未完成，未将其计为通过；隔离预览服务已停止，未操作用户业务数据库或调用真实模型。

第三批验证记录（2026-10-30）：账号管理、登录及账号基础专项测试共 30 个通过，其中账号管理新增 12 个。完整 `mvn '-Dmaven.repo.local=.m2/repository' verify` 成功，90 个后端测试通过、0 失败/错误/跳过，Checkstyle 为 0 违规，打包成功。上述四份前端行为测试共 37 个通过，全部应用脚本通过 `node --check`。本次浏览器自动化仍因无法写入内核资源而未能启动，未进行实际浏览器点击验收；没有启动预览进程，未访问用户数据库或真实模型。

第四批验证记录（2026-10-30）：新增角色与 SSE 专项 15 项通过，最终完整 verify 成功，105 个后端测试通过、0 失败/错误/跳过，Checkstyle 为 0 违规，打包成功。四份前端行为测试共 39 项通过，全部脚本语法检查通过。验证范围、共享分区及已受理请求的取消边界见 [ROLE-SHARING.md](ROLE-SHARING.md)，未进行真实模型或浏览器点击联调。

第五批验证记录（2026-10-30）：新增随机端口真实 HTTP 工作流，模型普通/流式请求均通过现有客户端访问回环地址的模拟供应商。首次运行发现 OpenAPI 创建账号缺少 201 声明，补齐该接口及相关 204 声明后，专项 1 项完整通过，包含账号、权限、聊天、共享记录、SSE、报表下载、会话撤销和接口文档检查。最终 `mvn '-Dmaven.repo.local=.m2/repository' verify` 成功，106 个后端测试通过，0 失败/错误/跳过，Checkstyle 0 违规，打包成功。五份前端测试共 41 项通过、9 个应用脚本语法检查通过。未操作用户数据库，未调用收费模型；本次重试浏览器与 Windows 备用运行时仍失败，实际点击和视觉验收未执行。完整状态、命令和页面验收步骤见 [ACCOUNT-DELIVERY.md](ACCOUNT-DELIVERY.md)。
