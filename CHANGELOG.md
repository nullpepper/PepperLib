# Changelog

All notable changes to PepperLib are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本语义见
[README API 稳定性策略](README.md)。

## [Unreleased]

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
