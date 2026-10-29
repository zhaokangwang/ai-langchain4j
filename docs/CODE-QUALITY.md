# 代码质量约束

本项目使用 Maven Checkstyle Plugin 3.6.0，显式固定检查引擎为 Checkstyle 12.3.1。检查工具仅属于 Maven 构建插件，不增加应用运行时依赖，不改变 Spring Boot 或 LangChain4j 版本。

选择依据：项目使用 JDK 17；Checkstyle 12 系列支持该运行环境，13/14 系列要求 JDK 21。Maven 插件默认带的检查引擎较旧，因此在插件依赖中单独固定引擎版本。

## 执行方式

在项目根目录使用 PowerShell，确保 Maven 使用 JDK 17。若本机默认 Java 路径不可用，可使用：

```powershell
$env:JAVA_HOME = 'C:\Users\wzk\.jdks\jbr-17.0.14'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 只运行代码检查
mvn '-Dmaven.repo.local=.m2/repository' checkstyle:check

# Checkstyle、编译、测试、打包
mvn '-Dmaven.repo.local=.m2/repository' verify
```

Checkstyle 绑定 Maven `validate`，所以 `compile`、`test`、`package`、`verify` 都会先检查。单独执行 `checkstyle:check` 不启动应用、不调用模型、不编译或运行测试。首次运行可能需要下载插件及其依赖。

检查覆盖 `src/main/java` 和 `src/test/java`，不检查第三方 vendor、资源文件或生成源码。违规按 error 处理，允许违规数为 0。机器可读结果在 `target/checkstyle-result.xml`，缓存和结果均在 `target/` 下。

## 首版规则

规则入口为 `config/checkstyle/checkstyle.xml`，包依赖限制为同目录下的 `import-control.xml`。

| 方面 | 当前约束 |
| --- | --- |
| 基础可读性 | UTF-8、文件末尾换行、禁止 Tab；类型和方法命名；外部类型与文件名匹配，单文件一个顶层类型 |
| 无效导入 | 禁止重复/多余导入、未使用的显式导入 |
| 错误写法 | 禁止空语句；空 catch 必须有解释注释；检查 equals/hashCode 配对及 switch 意外贯穿 |
| 日志 | 禁止直接使用 `System.out`、`System.err` 和 `printStackTrace()`，沿用 SLF4J |
| 文件体积 | 最多 500 行 |
| 方法体积 | 最多 120 个非空行，注释行仍计入；不能靠压缩多条语句规避 |
| 参数数量 | 普通方法及构造器最多 8 个参数；record 组件不受该规则限制 |
| 圈复杂度 | 每个方法/构造器/初始化块最多 30，无存量豁免 |
| 嵌套深度 | `NestedIfDepth.max=4`，按 Checkstyle 的嵌套 if 深度计数 |
| Controller 依赖 | 禁止导入 JDBC、Repository、模型适配器、工具、技能、身份组件及 LangChain4j 包 |
| Service / report / model | 禁止导入 JDBC 和 Controller；model 也不反向依赖 Service |
| Repository | 禁止导入 Controller、Service 和模型适配器；共享契约位于业务包 |
| skill / tool | 禁止导入 JDBC、Controller 和 Repository；需要数据库的业务工具经 Service 调用 |

Controller 的模型能力、数据访问和身份/技能能力查询均经 Service 调用。历史、报表及审计 SQL 已迁到 Repository；跨层 DTO 已提取到业务包，已移除 Service 直接 JDBC 和反向引用 Controller 的存量例外。

初期不直接套用完整 Google 或 Sun 配置：现有代码保留四空格缩进、record、长 SQL 和紧凑代码；暂不统一限制行宽、强制 Javadoc、重排全部 import、禁止所有通配符导入或实施全项目格式化。通配符导入也会限制未使用导入检查的精度，需要专项清理后再收紧。

## 修改和豁免原则

修复当前任务引入的违规，不用提高阈值、关闭规则、排除整个业务包、添加跳过检查参数等方式让构建通过。不启用行内关闭检查的过滤器。

`SkillRouter.route` 已拆分为对象识别与权限检查、候选召回、报表分支、候选归一化、上下文追问和结果决策。原有复杂度 70 的 XPath 例外及额外上限均已删除；当前所有主代码和测试统一适用 30 上限，不配置 suppression。

确需调整规则时，单独说明触发场景、可替代方案及影响，经讨论后修改规则与本文档。复杂度上限属于初期保护，应结合业务拆分逐步收紧。

Checkstyle 处理单个源文件，不能保证完整架构或业务安全：import 检查无法覆盖全限定类型名、反射或所有间接调用；复杂度不能发现多条语句压在一行的问题。权限过滤、事务原子性、接口兼容、SQL 安全和模型工具授权仍需测试和代码审查。若后续需要跨类依赖验证，可单独讨论 ArchUnit；本次不额外引入。

## IDEA

Maven 构建是统一检查入口，不要求安装 IDE 插件。若使用 CheckStyle-IDEA，选择项目兼容的 Checkstyle 12.3.1 引擎，加载 `config/checkstyle/checkstyle.xml`，并将规则属性 `checkstyle.config.dir` 设置为本项目 `config/checkstyle` 的绝对路径；IDE 插件无法选择该引擎时，以 Maven 命令结果为准。

## 接入验证

2026-10-29 使用 JDK 17.0.14、Maven 3.9.9 验证：重构后 Checkstyle 为 0 违规，完整 `verify` 成功，59 个后端测试通过；20 个前端行为测试及应用 JavaScript 语法检查通过。新增测试覆盖 1005 条请求的三批启动恢复、重复恢复不重复写事件，以及不修改已完成请求。首次接入时也已验证 Controller 越层导入、直接控制台输出、空 catch、参数过多及圈复杂度超限能阻断 `validate`。

## 官方依据

- [Checkstyle JRE/JDK 兼容表](https://checkstyle.sourceforge.io/index.html#JRE_and_JDK)
- [Checkstyle 12.3.1 发布记录](https://github.com/checkstyle/checkstyle/releases/tag/checkstyle-12.3.1)
- [Maven Checkstyle 插件：覆盖检查引擎版本](https://maven.apache.org/plugins/maven-checkstyle-plugin/examples/upgrading-checkstyle.html)
- [Maven Checkstyle 插件：构建中阻断违规](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html)
- [ImportControl 的功能与范围](https://checkstyle.org/checks/imports/importcontrol.html)
