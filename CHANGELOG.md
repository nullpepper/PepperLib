# Changelog

All notable changes to PepperLib are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本语义见
[README API 稳定性策略](README.md)。

## [Unreleased]

### 修复

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
