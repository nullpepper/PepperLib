# PepperLib

PepperUnion 与 PepperClaim 共享的协议 / 模型 / 基础设施原语库。

## 定位

- **内部共享库**：只提取两个插件已经拥有一致语义的代码（去重契约，不提前创建万能接口）。
- **依赖方向**：`PepperClaim → PepperLib`、`PepperUnion → PepperLib`；lib 零插件引用
  （`SourceDependencyGuardTest` 守卫）。lib 可独立发布、独立构建。
- **运行形态（双模式，见 docs/pepperlib-dual-loading-and-consumer-migration.md）**：
  - **前置插件模式**：服务器安装 `pepper-lib-plugin` 子项目产出的 `PepperLib.jar`
    （未 relocate 的 `io.pepper.lib.*` 单一实例）；PepperClaim / PepperUnion 以
    `compileOnly` 坐标依赖 + `paper-plugin.yml` 声明 `PepperLib` 为必需前置
    （`load: BEFORE`），启动时经 `ServicesManager` 校验 `PepperLibRuntime`。
  - **shade 模式**：第三方消费者以普通库坐标 + shadow relocate 到私有命名空间，
    不安装前置插件（示例见 `pepper-lib-shaded-example`）。
- **生态边界**：编译基线 Paper API 26.1（Java 17 字节码）；**自适应加载**：前置插件
  在旧版 Paper 服务器（1.18.2 ~ 1.20.6）可正常加载，自动禁用 gui-host 能力
  （详见下方「低版本服务器支持」）。
  `gui` / `confirm` / `i18n` /
  `economy` / `papi` / `task` 的公共签名耦合 Bukkit 类型；`money` / `validation` / `storage`
  为纯 Java 模块（详见各包 `package-info` 耦合度标注）。

## 低版本服务器支持（自适应加载）

- **检测与决策**：前置插件 `onEnable` 扫描 classpath 中 `io.pepper.lib` 类的
  `@MinMinecraftVersion` 类级注解（`io.pepper.lib.runtime.MinMinecraftVersion`），
  聚合出「能力 → 最低版本」注册表，再用 `Bukkit.getMinecraftVersion()`（纯版本号，
  新旧格式 `1.18.2` / `26.1.2` 统一由 `io.pepper.lib.runtime.ServerVersions` 数值比较）
  决策能力集——新增特性只需给类贴注解，无需改决策代码。
- **能力**：`PepperLibRuntime.supports("gui-host")`——低于 1.21 的服务器不声明此能力
  （`GuiHolder` / `GuiHost` / `PageHolderAdapter` 标注
  `@MinMinecraftVersion("1.21", "gui-host")`；其公共签名引用 `InventoryView`，
  该类型在 1.20.x 为 class、1.21+ 为 interface，形态不匹配的运行时执行会抛
  `IncompatibleClassChangeError`）；`PageWindow` / `Pagination` / `GuiEventGuards` 等
  其余 gui 类型、task / confirm / i18n / economy / papi / storage / money / validation
  全部不受影响。
- **消费者契约**：低版本服务器上，消费者必须查询 `supports("gui-host")` 后再决定
  是否创建/注册 `GuiHost` 监听器与实现 `GuiHolder`；不查询直接调用会在执行期崩溃
  （消费者自担）。
- **描述符**：前置插件使用 `plugin.yml`（`api-version: '1.18'`）而非 `paper-plugin.yml`
  ——Paper 26.x 对 paper-plugin.yml 有 api-version 下限校验（1.18 too old），而
  plugin.yml 的 `'1.18'` 在 1.18.2 ~ 26.x 全区间被接受（真实服务器验证）。

## 内容（公共 API，0.5.0）

| 包 | 类型 | 状态 |
|---|---|---|
| `io.pepper.lib.runtime` | `PepperLibRuntime` / `ServerVersions` / `MinMinecraftVersion` | 前置插件经 ServicesManager 注册的稳定运行时服务（版本/能力诊断）；类级最低版本声明注解 |
| `io.pepper.lib.expression` | `SafeExpression` / `PlaceholderVariableMapper` | 稳定（源自 PepperBotCustomMessage 提取；安全布尔表达式 + 占位符改写，纯 Java） |
| `io.pepper.lib.util` | `CooldownTracker` / `Hashing` | 稳定（源自 PepperBotCustomMessage 提取；per-key 冷却槽 + SHA-256，纯 Java） |
| `io.pepper.lib.task` | `PepperScheduler` / `BukkitPepperScheduler` / `ThreadGuard`(Instance) | 已接入（两插件） |
| `io.pepper.lib.storage` | `SqlDialect` / `Migration` / `MigrationRunner` / `StorageException` | 已接入（两插件迁移框架） |
| `io.pepper.lib.gui` | `PageWindow` / `Pagination` / `GuiEventGuards` / `GuiClick` / `GuiSessionId` / `GuiPage` / `GuiContext` / `GuiHost` | 已接入（两插件 GUI） |
| `io.pepper.lib.confirm` | `ConfirmEntry` / `ConfirmRegistry` / `ConfirmCleanupListener` | 已接入（两插件二次确认） |
| `io.pepper.lib.i18n` | `LanguageBundle` / `TextValue` / `PlaceholderResolver` | 已接入（两插件 i18n） |
| `io.pepper.lib.money` | `Amounts` | 已接入（两插件金额） |
| `io.pepper.lib.economy` | `VaultSupport` | 已接入（两插件 Vault 解析） |
| `io.pepper.lib.papi` | `PapiExpansionSupport` | 已接入（两插件 PAPI 注册） |
| `io.pepper.lib.validation` | `Preconditions` | 稳定（lib 内部使用；插件侧无直接消费者） |
| `io.pepper.lib.gui` | `GuiItemFactory` | **Experimental**：无插件消费者，菜单迁移时渐进接入 |

## API 稳定性策略

- **0.1.x**：只做兼容修复（bug、文档、内部实现调整）；不新增 API、不破坏签名。
- **0.2.x**：可新增 API；可调整 Experimental API；已接入 API 的破坏性变更需迁移指南。
- **1.0.0**：全部已接入 API 冻结为稳定契约；Experimental 项收敛（接入或删除）。
- 二进制兼容由 japicmp 任务守护（基线 = 上一发布版本，见 `build.gradle.kts`）；
  已纳入 `./gradlew check` 绿门——基线 jar 缺失时跳过并告警（fresh 环境/CI 无本地
  发布历史），发布后可用 `PEPPER_LIB_BASELINE_JAR` 指向上一版本产物使其生效。

## 构建

```bash
./gradlew check              # 测试 + spotless + javadoc + 产物守卫 + japicmp（绿门）
./gradlew :pepper-lib-plugin:shadowJar          # 前置插件 PepperLib.jar
./gradlew :pepper-lib-shaded-example:shadowJar  # shade 示例消费者
bash scripts/paper-smoke.sh thin               # 真实 Paper 三件套启动 smoke
bash scripts/paper-smoke.sh shaded             # 真实 Paper shade 模式 smoke
./gradlew publishToMavenLocal
```

- Java 25 toolchain（GraalVM CE，见 `gradle.properties`）。
- 构建约定集中在 `buildSrc`（`pepper.java-conventions` / `pepper.spotless`）；
  依赖/插件版本单一来源 `gradle/libs.versions.toml`（升级只改目录一处）。
- Spotless palantirJavaFormat 与两插件一致；javadoc 纳入 `check` 防文档腐化。
- TDD 纪律：所有行为改动先红后绿。
- CI：`library-check`（构建+守卫）+ `thin-consumer-paper-smoke` + `shaded-consumer-paper-smoke`。

## 发布

```bash
./gradlew publish            # 发布普通库到内部仓库（见 build.gradle.kts publishing 配置）
./gradlew publishToMavenLocal # 本地验证
```

- 普通库坐标 `io.pepper:pepper-lib:<version>`（编译/shade 输入，不能放入 `plugins/`）。
- 前置插件产物 `PepperLib.jar`（`pepper-lib-plugin/build/libs/`）单独分发到服务器 `plugins/`。
- 发布前必须通过：普通库测试与 Javadoc、前置插件产物检查、两插件前置模式 Paper 启动、
  shade 示例启动、japicmp 二进制兼容（见重构文档 §10）。
