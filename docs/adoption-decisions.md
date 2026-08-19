# PepperLib 完全接入：实施决策记录

> 依据 PepperLib-Full-Adoption-Plan.md 实施过程中产生的需要用户决策/知悉的问题。
> 每项标注状态：**已实施（默认采纳）** / **待用户决策**。

## 1. Confirm 过期提示语义差异（已实施，默认采纳）

**问题**：lib `ConfirmRegistry.consume()` 对过期条目返回 `empty`（并清理）；Union 本地
`consume()` 返回过期条目由 `ConfirmCommand` 判断并回复 `CONFIRM_EXPIRED`。迁移后
过期时提示从「确认已过期」变为「没有待确认操作」（CONFIRM_NONE）。

**影响**：仅在 10 秒窗口恰好超时的毫秒级竞态出现；lib 语义更健壮（过期条目自动清理）。
计划 §4.5 原文「过期判断用 entry.isExpired()」基于旧 consume 语义，实际 lib 已过滤过期。

**如需保留 EXPIRED 提示**：需改 lib `consume` 语义（返回过期条目由消费方判断），
或给 `ConfirmCommand` 增加「刚被清理的过期条目」区分——不建议，收益为一条提示文案。

## 2. GuiContext 契约扩展：newInventory（已实施，默认采纳）

**问题**：`GuiManager.openGui` 要求库存 holder 是页面适配器（事件转发 `getInventoryHolder`
依赖），但 `GuiPage.render(context, state)` 拿不到适配器引用。

**方案**：`GuiContext` 增加 `Inventory newInventory(int rows, String title)`——holder 由
管理器注入；页面渲染必须经 context 创建库存（`PageHolderAdapter` 校验 holder，否则抛
`IllegalStateException`）。这是 lib 契约扩展（阶段 6.1），已写入 GuiPage Javadoc。

## 3. ConfirmMenu 倒计时改递归 runTask（已实施，默认采纳）

**问题**：`PepperScheduler.runRepeating` 无任务句柄（void），无法在解锁后 cancel。

**方案**：确认菜单倒计时改为递归 `runTask`（每 20 tick 调度自身，解锁/离线/换页自终止）。
生产语义与 Bukkit runTaskTimer 等价；代价是每 tick 一个任务对象（仅确认菜单开启的 5 秒内）。

## 4. PepperLib 发布与插件 CI（**待用户决策**）

**现状**：
- lib 0.1.0 已 `publishToMavenLocal`；两插件已切换坐标依赖（本地构建全绿）。
- **PepperLib 仓库没有 git remote**（未推送 GitHub）。插件 CI 已加
  `checkout nullpepper/PepperLib → publishToMavenLocal` 步骤——**在 lib 推送前插件 CI 会挂**。

**选项**：
- **A（推荐）**：推送 PepperLib 到 `github.com/nullpepper/PepperLib`，CI 步骤即可用；
  后续 lib 迭代由插件 CI 自动构建发布。
- **B**：配置内部 Maven 仓库（设置 `PEPPER_MAVEN_URL` 环境变量），lib 发布到远程仓库，
  插件 CI 从仓库解析坐标（无需 checkout 步骤）。
- **C**：插件 CI 保持 composite build（`includeBuild`），仅发布流程用坐标——与计划 §7.5 冲突。

## 5. 无单测菜单的迁移验证（已实施，知悉）

AuditMenu / InviteMenu / ConfigMenu / GiveMemberMenu / RankAssignMenu / AcceptInviteMenu /
FindGuildMenu 无行为单测；迁移靠逐行等价转换 + 协议层测试（PageHolderAdapterTest /
GuiManagerOpenPageTest / ConfirmMenuBehaviorTest / PagedMenuSupportTest）覆盖事件链路。
建议后续补 MockBukkit 抽测（计划风险清单已列）。

## 6. 正式发布目标（**待用户决策**）

0.1.0 发布位置：mavenLocal（已做）。正式发布（插件坐标解析）目标：
- 内部 Maven 仓库 URL（推荐，`PEPPER_MAVEN_URL` 注入）
- 或 GitHub Releases + JitPack（lib 为 java-library，不产 shadow jar，JitPack 可解析）
- 或 Maven Central（需 sonatype 账号，0.1.0 内部库阶段不必要）

## 7. 阶段 6 验收核对（已实施）

- ✅ GuiManager 不再向页面暴露完整 Bukkit 事件（全部经 GuiClick）
- ✅ GuiSessionId 打开生成 / 关闭失效（测试覆盖）
- ✅ 插件禁用 / 玩家退出 / 顶部点击 / 拖拽行为不变（onDisable/onPlayerQuit 路径逐条核对）
- ✅ 15 个菜单全部迁移，AbstractGui / PagedMenu 已删除，PageGuide 已去事件化
- ⚠️ 行为等价依赖代码转换核对（见第 5 条）
