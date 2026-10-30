# 账号功能：第五批联调与交付记录

## 当前状态

第一至第四批的账号登录、管理员账号维护、三个业务角色的 Skill 授权和角色共享数据已经接入。本批补充真实 HTTP 全流程联调、页面模板与原生表单约束检查，并修复检查发现的问题。

本批自动化验收和打包已完成。首次真实 HTTP 联调发现 OpenAPI 成功状态码声明缺失，修复后专项测试已通过；最终 `mvn verify` 成功，106 个后端测试全部通过，Checkstyle 为 0 违规，已生成可运行 jar。前端 41 项测试和 9 个应用脚本语法检查通过。

实际浏览器点击和视觉验收仍受工具环境故障影响：本次重试浏览器工具仍报内核资源写入失败，Windows 备用运行时初始化也连续两次异常退出。此项保留为未执行，不计入自动化通过结果；恢复工具后可按文末流程补验。

## 已交付的使用流程

1. 访问 `/` 或 `/reports.html`，未登录进入 `/login.html`。首次初始化管理员为 `admin/admin`；首次创建前可用 `APP_ADMIN_PASSWORD` 覆盖初始密码。已有管理员的密码不会被启动过程或环境变量重置。
2. 首次登录进入 `/account.html` 修改密码，再重新登录。管理员从账号栏进入 `/admin-accounts.html`。
3. 管理员可以按关键词、角色、启用状态分页查询账号，创建普通账号、重置密码、禁用或启用账号。账号创建时选择 buyer、service、warehouse 之一；新建和重置密码后均要求下次登录改密。
4. 普通账号完成初始改密后进入聊天或报表。本人可改密和退出，普通账号不能访问管理页面和管理 API。

| 角色 | Skill 数 | 业务范围 | 会话和报表分区 |
| --- | --- | --- | --- |
| buyer | 6 | A1001、A1002、A1003 | demo-user |
| service | 8 | AS2001 工单及关联订单 A1001 | demo-service |
| warehouse | 3 | WH01 库存及订单 A1002 | demo-warehouse |

管理员是独立管理标记，预设管理员的业务角色为 buyer，不额外获得其他角色的 Skill。同角色共享历史、执行记录和报表，不按账号隔离；跨角色的会话和报表不可见。不同角色的订单业务范围可以有重叠。具体边界见 [ROLE-SHARING.md](ROLE-SHARING.md)。

禁用账号后不能登录，旧登录在后续请求或流式复核时失效；重新启用不能复活旧登录。管理员重置或本人改密会使该账号全部旧登录失效，同角色其他账号仍能访问共享记录。退出只注销当前 Session。预设管理员和当前操作人不可被禁用。

## 本批新增与修复

- 新增 `AccountWorkflowHttpIntegrationTest`：启动随机端口的真实 Tomcat，使用 Java HttpClient 和独立 CookieJar 从外部 HTTP 入口完成账号工作流，不使用 MockMvc、模拟登录或 Repository 写入夹具。数据库为测试配置中的隔离 H2 内存库。
- 新增 `WorkflowModelStub`：仅监听 `127.0.0.1` 的 OpenAI 协议模拟供应商，应用仍使用自动配置的普通和流式 LangChain4j 客户端。测试验证实际网络传输和中文 SSE，不调用收费模型。
- 新增 `scripts/page-contract-check.cjs`：使用项目自带 Vue 编译器编译聊天、报表、登录、改密和账号管理五个页面的模板；按 Unicode Sets `v` 规则验证 HTML 表单 pattern。无需安装 npm 依赖。
- 修复创建账号表单的 pattern：字符组中的短横线使用转义，避免浏览器按 `v` 规则解析时报错，保持原账号格式要求。
- 为账号创建明确声明 OpenAPI 成功状态码 201；为管理员重置密码、退出登录和本人改密明确声明 204 及无响应正文，保持实际 HTTP 契约不变。

本批不新增依赖或数据库迁移，不修改已应用迁移，不删除现有账号、会话、报表或用户 `data/`。账号级隔离、账号删除、注册找回、修改既有账号角色及管理多个管理员均不在本次约定范围。

## 验证记录（2026-10-30）

| 检查 | 实际结果 |
| --- | --- |
| 修复后的真实 HTTP 全流程专项 | 1 项通过，0 失败/错误/跳过；完整工作流及 OpenAPI 201/204 声明检查均通过 |
| 真实 HTTP 业务步骤 | 管理员首次改密、Cookie 属性与 Session 轮换、CSRF、三个角色创建与 Skill 数量、管理权限、分页重名、JSON 聊天、同角色历史/幂等重放、跨角色拒绝、中文 SSE、三个角色报表及真实 Excel 文件解析、禁用再启用、密码重置和退出均通过 |
| 最终 Maven verify | BUILD SUCCESS；106 个后端测试通过，0 失败/错误/跳过 |
| 最终 Checkstyle | 0 违规，未跳过检查或增加豁免 |
| 打包 | 已生成 `target/ai-langchain4j-0.0.1-SNAPSHOT.jar`，大小 59,265,013 字节 |
| 五份 Node 前端行为与页面契约测试 | 41 项通过，0 失败/跳过 |
| 应用 JavaScript 语法检查 | `static/js` 下 9 个文件全部通过 |
| Git 空白检查 | `git diff --check` 通过；只有工作区换行转换提示 |
| 实际浏览器点击与视觉验收 | 未执行：重试浏览器工具仍报 `failed to write kernel assets` / 系统找不到指定路径；Windows 备用运行时两次报 `trusted Node process exited unexpectedly` |

最终验证使用 JDK 17.0.14、Maven 3.9.9。新增专项已经单独重跑通过，并再次随完整构建通过；全量测试使用隔离数据库和模拟模型，不访问用户 `data/` 或收费模型。真实 HTTP 和模板编译检查不能替代浏览器点击验收，也不验证真实模型回答质量。

本批执行通过的验证命令如下，其中前端测试和语法检查沿用本批此前已通过的结果，后续只修改文档，没有重复执行；后端专项与完整构建已在本次收尾中补跑通过。

```powershell
$env:JAVA_HOME = 'C:\Users\wzk\.jdks\jbr-17.0.14'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=AccountWorkflowHttpIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' verify

node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs scripts/auth-frontend-check.cjs scripts/admin-accounts-frontend-check.cjs scripts/page-contract-check.cjs

$sourceScripts = Get-ChildItem -LiteralPath src/main/resources/static/js -Filter '*.js' -File
foreach ($sourceScript in $sourceScripts) {
    node --check $sourceScript.FullName
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
git diff --check
```

最终 HTTP 专项日志为 `target/account-workflow-http.log`，完整构建日志为 `target/account-final-verify.log`，JUnit 报告位于 `target/surefire-reports/`。测试 Tomcat、内存库连接池和本地模拟供应商均随测试结束关闭，没有启动连接用户文件库的预览实例。

## 启动与页面验收

沿用项目已有模型环境变量配置，密钥仅保留在后端环境中。当前构建已通过，可从项目根目录运行：

```powershell
java -jar target/ai-langchain4j-0.0.1-SNAPSHOT.jar
```

默认端口为 18080，可由 `SERVER_PORT` 覆盖。默认数据库仍为启动目录下的 `data/customer-service.mv.db`，重启后账号及业务记录保留，Session 需要重新登录。不要为验收删除数据库；已有 admin 请使用自己此前设置的密码。

浏览器工具恢复后，还需按下列流程做页面验收：

1. 管理员登录、初始改密（仅首次），打开账号管理；创建三个角色和第二个 buyer 账号，检查列表筛选、翻页、重复账号提示。
2. 使用独立浏览器会话分别登录普通账号，完成初始改密，确认没有管理入口，直接访问管理页/API 被拒绝。
3. 对照上表检查 Skill 和业务示例；发送问题，观察 SSE 与停止操作，刷新查看历史和实际发起账号。聊天会调用已配置模型，应在自行安排的模型验收中执行。
4. 使用第二个 buyer 查看同角色会话和报表；使用 warehouse 访问 buyer 的会话/快照应不可见。报表日期选 2026 年 10 月，检查 Excel 下载和内容。
5. 管理员禁用再启用普通账号，验证旧登录不恢复；重置密码后验证旧登录失效、新密码登录要求改密。同角色其他账号仍可用。
6. 检查多标签页登录/退出刷新、错误提示、弹窗密码清空，以及页面在实际窗口尺寸下的布局。

账号 API、密码边界和 Session 规则见 [ACCOUNTS.md](ACCOUNTS.md)。
