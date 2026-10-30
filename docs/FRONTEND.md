# 回答阅读体验与调用记录

账号第二批已接入登录页 `/login.html`、本人改密页 `/account.html` 和聊天/报表页顶部的账号栏。未登录访问业务页会跳到登录页；首次管理员登录要求先改密。`auth.js` 为包括 SSE 在内的业务写请求添加 CSRF，登录过期返回登录页，不自动重发业务请求。使用边界与接口见 [ACCOUNTS.md](ACCOUNTS.md)。

第三批增加管理员专用 `/admin-accounts.html`，支持分页筛选、添加普通账号、重置密码和禁用/启用。账号栏根据服务器返回的管理员标记显示入口；管理页及接口都有后端权限校验。交互使用原生 dialog，保存时防止重复提交，密码不会在列表回显或存到浏览器。

第四批已将登录角色接入 Skill 和角色共享数据。能力面板显示实际账号及共享分区，历史授权摘要保留当时的 actor，不用当前账号覆盖。同角色刷新保留当前共享会话，跨角色刷新清除旧视图。登录、退出和本人密码变化通过只含 changed 的 BroadcastChannel 通知其他标签页刷新，不发送账号或凭据；后端权限校验始终生效。详见 [ROLE-SHARING.md](ROLE-SHARING.md)。

第五批增加 `scripts/page-contract-check.cjs`，使用实际本地 Vue 编译器检查五个页面模板，并按 Unicode Sets `v` 规则检查 HTML 原生 pattern。发现并修复了创建账号表单短横线未转义的兼容问题。该检查没有 DOM 挂载或浏览器点击能力，不能代替视觉和交互验收。

多角色技能第三阶段新增身份与授权技能面板、实时路由卡片和按尝试的历史摘要，见 [SKILL-UI.md](SKILL-UI.md)。

重启 Spring Boot 后访问 http://localhost:18080/ ，必要时 Ctrl+F5 刷新本地脚本。

## 阅读和复制

- 助手回答支持 Markdown：标题、加粗、斜体、列表、引用、分隔线、表格、链接、行内代码和围栏代码块。
- 代码块显示语言名称，“复制代码”只复制该代码块文字，保留缩进和换行。
- “复制回答”复制原始 Markdown，不复制页面按钮或工具摘要，便于粘贴到笔记中。
- 复制成功会给出反馈；剪贴板 API 不可用时尝试浏览器兼容复制，仍失败时提示手动选择，不假报成功。
- 向上阅读历史时，新回答返回不强制跳底；“回到最新消息”允许主动返回。加载更早消息保持原阅读位置。
- 新发送问题和主动切换会话会定位到当前会话底部。

可向模型提问：

> 请用 Markdown 解释 Spring Boot 的依赖注入，包含一个 Java 代码块、一张组件作用表和一个列表。

过去存入数据库的 Markdown 回答也会直接以新样式展示，无需重新调用模型或修改数据库内容。

## 本轮工具调用

点击助手回答下方“本轮工具调用”展开摘要；首次展开通过已有 GET /api/chat-runs/{requestId} 按需读取，重复展开使用本页缓存。
显示实际记录的工具名、技能名、执行状态和耗时。工具未执行时明确显示“模型直接回答”，不补造步骤。
详细的模型返回、输入快照、工具参数与结果、技能摘要和历史尝试仍可在“查看执行记录”面板查看。
这些操作只读取数据库，不重新执行技能或调用模型。第四步新增了实时回答和执行中的工具状态；完成后的工具摘要仍从数据库读取，详见 [STREAMING.md](STREAMING.md)。

## 文件与边界

- vendor/marked.umd.js：固定版本 Marked 18.1.1。
- vendor/purify.min.js：固定版本 DOMPurify 3.4.17。
- js/content.js：Markdown 解析后过滤，再通过固定 DOM 结构添加代码工具栏。
- js/chat.js：复制反馈、按需工具摘要和滚动状态。
- css/content.css：Markdown 表格、代码块等排版。

所有依赖均本地提供，无需构建或 CDN。模型返回的原始 HTML 显示为文字，解析结果按允许列表过滤；不执行 Vue 模板、脚本、事件属性或 iframe。Markdown 图片显示描述而不加载图片。链接只允许 http/https 和页面锚点，外链使用新标签并隔离 opener。
记录面板中的原始模型/工具 JSON 继续使用 Vue 文本插值，不作为 Markdown 或 HTML 执行。
渲染库加载失败时使用转义后的文本显示，不直接插入未过滤 HTML。

完整前端检查命令为 `node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs scripts/auth-frontend-check.cjs scripts/admin-accounts-frontend-check.cjs scripts/page-contract-check.cjs`，覆盖聊天、报表、认证、账号管理行为以及模板和表单契约，本批共 41 项通过。另对 `static/js` 全部 9 个脚本执行 `node --check` 通过。真实 HTTP 测试验证资源访问、Cookie 和业务接口；浏览器自动化因内核资源启动故障未能运行，未将实际点击和视觉验收计为通过。当前验收状态见 [ACCOUNT-DELIVERY.md](ACCOUNT-DELIVERY.md)。
