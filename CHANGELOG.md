# Changelog

All notable changes to PepperLib are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本语义见
[README API 稳定性策略](README.md)。

## [0.6.0] - 2026-08-20

### 新增

- **版本比较工具** `io.pepper.lib.runtime.LibVersions`（设计评审 §4.3）：
  - `parse`：semver 风格 1~3 段版本（`"0.5.0"` / `"0.5"` / `"1"`）解析为 `[major, minor, patch]`
    数值数组，缺段补 0；非法输入（null/空白/非数值/空段/4 段以上/负数）抛
    `IllegalArgumentException`；
  - `atLeast(actual, minimum)`：分段数值「实际 &gt;= 最低」比较（缺段按 0 补，
    `"0.5.0"` 与 `"0.5"` 等价）。
- **运行时「≥ 最低版本」校验**：`PepperLibRuntime.atLeast(String)` default 方法
  （委托 `LibVersions`）——替代消费者侧 `apiVersion().startsWith(...)` 前缀匹配：
  0.x 阶段前缀近似 minor 契约，1.0 冻结后破坏性 major 升级会被前缀误放行。
  兼容注意：default 方法为纯增量（japicmp），但旧版 lib 运行时不存在该方法，
  前置模式消费者升级 lib 与消费者必须同步（lib 先行）。

### 变更

- 版本 0.5.0 → 0.6.0（前置插件 apiVersion 与发布坐标同步）；japicmp 基线切至 0.5.0。
- 消费者版本契约升级为「版本单一来源注入 + atLeast 校验」（Union/Claim/BindManager/
  CustomMessage 同步切换，见各消费者提交）。

## [0.5.0] - 2026-08-20

### 新增

- **安全表达式引擎** `io.pepper.lib.expression`（源自 PepperBotCustomMessage 提取，
  纯 JDK 零依赖）：
  - `SafeExpression`：白名单布尔表达式引擎（`&&` / `||` / `!` / 比较 /
    `contains` / `startswith` / `endswith`，字符串/数字/布尔字面量）——无反射、
    无类加载、无方法调用，源码长度（4096）与嵌套深度（64）有界，拒绝 RCE 面；
  - `PlaceholderVariableMapper`：条件串中 `<xxx>` 与 `%xxx%` 改写为合法变量名
    （非法字符 → `_`，清洗后同名冲突抛 `IllegalArgumentException`），
    记录 变量名 → 原始占位符名 映射。
- **通用纯 Java 工具** `io.pepper.lib.util`（源自 PepperBotCustomMessage 提取）：
  - `CooldownTracker`：per-key 冷却槽——`tryAcquire` 原子抢占、`release`
    remove-if-equals（防并发误删）、`shouldSendTip` 提示节流、`remainingMillis`、
    `clear`；窗口 `windowMillis <= 0` 视为禁用（直通语义与原版一致）；
  - `Hashing.sha256`：UTF-8 → 64 位小写 hex。

> 例外条款案例：表达式/工具四件套为单消费者（CustomMessage）提取，按 README
> 定位例外条款允许——防 RCE 安全原语属高价值稀缺能力，预期第二消费者。

### 变更

- 版本 0.4.0 → 0.5.0（前置插件 apiVersion 与发布坐标同步）；BindManager
  `REQUIRED_PEPPERLIB_API` 同步升级 0.5；japicmp 基线切至 0.4.0。

## [0.4.0] - 2026-08-20

### 新增

- **一次性验证码服务** `io.pepper.lib.verification.OneTimeCodeService`（源自
  PepperBotBindManager `VerificationManager` 提取，0.4.0）：
  - 泛型负载 `issue(payload, ttl)` / `consume(code)`（原子消费，并发同码只成功一次）/
    `peek`（非破坏性查看）/ `restore`（失败回滚放回）/ 每键冷却（`tryAcquireCooldown`
    原子获取 + `putCooldown` 无条件重置）/ `cleanupExpired` / 设置热替换
    （`updateSettings`，进行中验证码与冷却不丢失）；码长 [4,8]、秒数钳位。
  - `OneTimeCodeServiceConcurrencyTest` 随迁（同码并发单胜者、冷却单放行）。
- **JDBC 工具** `io.pepper.lib.storage`（源自 BindManagerImpl 提取，纯 JDK 零依赖）：
  - `SqlExceptions`：唯一键冲突（SQLState 23xxx / 消息兜底）与 transient busy
    （errorCode 5 / sqlite_busy / database is locked）分类；
  - `JdbcRetry.withConnectionRetry`：有限次退避重试（只重试 busy，唯一键冲突
    立即上抛；默认 3 次 / 50ms，可自定义）。

- **自适应加载**（docs/pepperlib-dual-loading-and-consumer-migration.md §4.1）：
  - `io.pepper.lib.runtime.ServerVersions`：服务器版本解析/比较纯函数——新旧格式
  - `PepperLibRuntime.CAP_GUI_HOST`（`"gui-host"`）能力常量：前置插件 `onEnable`
    按 `Bukkit.getMinecraftVersion()` 自动决策能力集（plugin 包内 `CapabilityResolver`
    纯函数）；低于 1.21 的服务器不声明 gui-host 并输出 warning——`GuiHolder` /
    `GuiHost` / `PageHolderAdapter` 的公共签名引用 `InventoryView`（1.20.x 为 class、
    1.21+ 为 interface，形态不匹配的运行时执行抛 `IncompatibleClassChangeError`）。
  - 前置插件描述符 `paper-plugin.yml` → `plugin.yml`（`api-version: '1.18'`）：
    Paper 26.x 对 paper-plugin.yml 有 api-version 下限校验（`1.18 too old`），
    plugin.yml 的 `'1.18'` 在 1.18.2 ~ 26.x 全区间被真实服务器验证可加载。
  - `onEnable` 改用 `getDescription().getVersion()`（`getPluginMeta()` 是 Paper
    1.19.4+ API，1.18.2 上不存在）。
  - CI 新增 `legacy-consumer-paper-smoke` job：Paper 1.18.2 + Java 17 真实服务器
    验证自适应加载（`scripts/paper-smoke.sh legacy` 模式）。
  - **注解驱动能力决策**：新增 `io.pepper.lib.runtime.MinMinecraftVersion` 类级注解
    （`value` 最低版本 + `capability` 能力名，只表达下限）；`GuiHolder` /
    `GuiHost` / `PageHolderAdapter` 标注 `@MinMinecraftVersion("1.21", "gui-host")`；
    前置插件 `CapabilityAnnotationScanner` 扫描 classpath（jar/目录两种形态，零依赖）
    聚合「能力 → 最低版本」注册表，`CapabilityResolver` 改为注册表遍历决策——
    新增特性只需贴注解，不再改决策代码；消费者 `supports("gui-host")` 契约不变。
    守卫测试（`MinMinecraftVersionGuardTest`）断言 gui-host 三类注解存在且阈值一致。

### 变更

- 版本 0.3.0 → 0.4.0（前置插件 apiVersion 与发布坐标同步，单一来源根项目
  version）；BindManager 依赖与 `REQUIRED_PEPPERLIB_API` 同步升级 0.4。

### 构建

- 构建基础设施集中化：`gradle/libs.versions.toml` 版本目录 + `buildSrc` 约定插件
  （`pepper.java-conventions`：toolchain 25 / `--release` 17 / 依赖解析 JVM 属性钉回 /
  公共仓库 / JUnit5；`pepper.spotless`：importOrder + palantirJavaFormat）——
  三个子项目 `build.gradle.kts` 去重，依赖与插件版本单一来源（升级只改目录一处）。
- `japicmp` 二进制兼容门纳入 `./gradlew check` 绿门：基线默认 mavenLocal 上一发布
  版本（0.4.0），`PEPPER_LIB_BASELINE_JAR` 可覆盖；基线 jar 缺失时跳过并告警
  （fresh 环境/CI 无本地发布历史）。
- 新增 `BuildInfraGuardTest` 守卫测试：`check` 必须依赖 `japicmp`；三个构建文件
  不得散落硬编码版本。

## [0.3.0] - 2026-08-20

### 新增

- **第三个消费者接入（前置模式）**：PepperBotBindManager 仿照 PepperClaim 前置模式
  接入——`compileOnly` 坐标依赖 + `plugin.yml` 声明必需前置 + `onEnable` 经
  ServicesManager 校验 `PepperLibRuntime`（`REQUIRED_PEPPERLIB_API`），复用 storage
  迁移框架 / task ThreadGuard / validation Preconditions。

### 变更

- 版本升至 0.3.0（前置插件 apiVersion 与发布坐标同步）。

## [0.2.0] - 2026-08-20

### 新增

- **双模式加载**（docs/pepperlib-dual-loading-and-consumer-migration.md）：
  - `pepper-lib-plugin` 前置插件子项目：产出 `PepperLib.jar`（Shadow 打包普通库、
    不 relocate；`paper-plugin.yml` + `PepperLibPlugin` 主类，`load: STARTUP`）。
  - `PepperLibRuntime` 稳定服务接口：前置插件经 ServicesManager 注册，消费者做
    版本/能力诊断，不依赖插件主类实现。
  - `pepper-lib-shaded-example` shade 模式示例消费者：relocate 到私有命名空间，
    无前置插件独立运行。
  - 产物守卫测试（普通库/前置插件/shade 消费者三份 JAR 内容契约）。
  - CI 拆分为 library-check / thin-consumer-paper-smoke / shaded-consumer-paper-smoke
    （真实 Paper 26.1.2 服务器启动验证，scripts/paper-smoke.sh）。
  - japicmp 二进制兼容门（基线 0.1.0；0.2.x 允许新增 API，禁止破坏性变更）。
- `CHANGELOG.md` 建立。

### 修复

- 软依赖守卫（`PapiExpansionSupport` / `PapiPlaceholderResolver`）catch 集补
  `LinkageError`：PAPI 存在但类损坏/版本错配时不再穿透中断插件（#H1）。
- `Amounts.tryParse` 增加 32 字符输入长度上限：拒绝超长数字串触发昂贵
  `BigDecimal` 解析（#L1）。
- `PapiExpansionSupport` 注册失败时输出 warning 日志（#L2）。
- `GuiHost` 事件守卫加固：shift 点击（`MOVE_TO_OTHER_INVENTORY`）与
  `COLLECT_TO_CURSOR` 先 cancel 再放行；关闭事件补归属校验。
- `MigrationRunner` 版本防呆升级：任何未知已应用版本（含中间缺失）都拒绝启动。

### 变更

- `ThreadGuard` 实例化（`ThreadGuard.Instance`）：两插件同服共享 lib 类加载
  时静态状态不再跨插件污染；静态入口保留为 `@Deprecated` 委托壳；Async/主线程
  标记改为深度计数，支持嵌套 enter/exit（#M1）。
- `LanguageBundle.reload()` 同步化（`synchronized`）；语言文件不再首启自动写盘，
  自定义语言从 JAR 提取模板放置（#M7）。
- `Preconditions` 转正：移除 Experimental 标注（#M5）。
- 各包新增 `package-info.java` 耦合度标注；README 补充生态边界声明（#M2）。
- Javadoc 中仓库内部文档路径引用改为无路径表述（发布版 javadoc 不再含死链）（#M6）。
- 版本升至 0.2.0。

### 文档

- `GuiSessionId.version` 明确为预留字段（当前恒 0，勿依赖递增语义）（#M4）。
- `MigrationRunner.rollbackAndRestore` 标注 `@VisibleForTesting` 并说明用途（#M3）。
- 双模式加载与消费者迁移方案（docs/pepperlib-dual-loading-and-consumer-migration.md）。

## [0.1.0] - 2026-08-20

- 软依赖守卫（`PapiExpansionSupport` / `PapiPlaceholderResolver`）catch 集补
  `LinkageError`：PAPI 存在但类损坏/版本错配时不再穿透中断插件（#H1）。
- `Amounts.tryParse` 增加 32 字符输入长度上限：拒绝超长数字串触发昂贵
  `BigDecimal` 解析（#L1）。
- `PapiExpansionSupport` 注册失败时输出 warning 日志（#L2）。

### 变更

- `ThreadGuard` 实例化（`ThreadGuard.Instance`）：两插件同服共享 lib 类加载
  时静态状态不再跨插件污染；静态入口保留为 `@Deprecated` 委托壳（0.1.x 兼容）（#M1）。
- `LanguageBundle.reload()` 同步化（`synchronized`），并发 reload 不交错（#M7）。
- `Preconditions` 转正：移除 Experimental 标注（#M5）。
- 各包新增 `package-info.java` 耦合度标注；README 补充生态边界声明（#M2）。
- Javadoc 中仓库内部文档路径引用改为无路径表述（发布版 javadoc 不再含死链）（#M6）。

### 新增

- `CHANGELOG.md` 建立（#M6）。
- CI 增加消费者冒烟 job：checkout PepperClaim / PepperUnion 构建验证 lib 接线（#M6）。

### 文档

- `GuiSessionId.version` 明确为预留字段（当前恒 0，勿依赖递增语义）（#M4）。
- `MigrationRunner.rollbackAndRestore` 标注 `@VisibleForTesting` 并说明用途（#M3）。

## [0.1.0] - 2026-08-20

初始共享库：`task`（PepperScheduler/BukkitPepperScheduler/ThreadGuard）、
`storage`（SqlDialect/Migration/MigrationRunner）、`gui`（PageWindow/Pagination/
GuiEventGuards/GuiClick/GuiSessionId/GuiPage/GuiContext/GuiItemFactory/GuiHost）、
`confirm`（ConfirmEntry/ConfirmRegistry/ConfirmCleanupListener）、`validation`
（Preconditions）、`i18n`（LanguageBundle/TextValue/PlaceholderResolver）、
`money`（Amounts）、`economy`（VaultSupport）、`papi`（PapiExpansionSupport）。
