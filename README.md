# PepperLib

PepperUnion 与 PepperClaim 共享的协议 / 模型 / 基础设施原语库。

## 定位

- **内部共享库**：只提取两个插件已经拥有一致语义的代码（去重契约，不提前创建万能接口）。
- **依赖方向**：`PepperClaim → PepperLib`、`PepperUnion → PepperLib`；lib 零插件引用
  （`SourceDependencyGuardTest` 守卫）。lib 可独立发布、独立构建。
- **运行形态**：lib 不打包 Bukkit/Paper 类型（编译期 `compileOnly`），由插件运行时提供；
  两插件经 Gradle composite build 或坐标依赖接入。

## 内容（20 个公共 API，0.1.0）

| 包 | 类型 | 状态 |
|---|---|---|
| `io.pepper.lib.task` | `PepperScheduler` | 已接入（两插件 Scheduler 子类型） |
| `io.pepper.lib.storage` | `SqlDialect` / `Migration` / `MigrationRunner` / `StorageException` | 已接入（两插件迁移框架） |
| `io.pepper.lib.gui` | `PageWindow` / `Pagination` / `GuiEventGuards` / `GuiClick` / `GuiSessionId` / `GuiPage` / `GuiContext` | 已接入（两插件 GUI） |
| `io.pepper.lib.confirm` | `ConfirmEntry` / `ConfirmRegistry` / `ConfirmCleanupListener` | 已接入（两插件二次确认） |
| `io.pepper.lib.validation` | `Preconditions` | **Experimental**：lib 内部使用，插件侧无直接消费者 |
| `io.pepper.lib.gui` | `GuiItemFactory` | **Experimental**：无插件消费者，菜单迁移时渐进接入 |

## API 稳定性策略

- **0.1.x**：只做兼容修复（bug、文档、内部实现调整）；不新增 API、不破坏签名。
- **0.2.x**：可新增 API；可调整 Experimental API；已接入 API 的破坏性变更需迁移指南。
- **1.0.0**：全部已接入 API 冻结为稳定契约；Experimental 项收敛（接入或删除）。

## 构建

```bash
./gradlew check   # 测试 + spotless + javadoc（绿门）
./gradlew publishToMavenLocal
```

- Java 25 toolchain（GraalVM CE，见 `gradle.properties`）。
- Spotless palantirJavaFormat 与两插件一致；javadoc 纳入 `check` 防文档腐化。
- TDD 纪律：所有行为改动先红后绿（110 → 98 套件随收敛调整）。

## 发布

```bash
./gradlew publish            # 发布到内部仓库（见 build.gradle.kts publishing 配置）
./gradlew publishToMavenLocal # 本地验证
```

发布后两插件从 composite build 切换为坐标依赖 `io.pepper:pepper-lib:<version>`。
