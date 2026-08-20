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

## 4. PepperLib 发布与插件 CI（**已解决 2026-08**）

- **用户决策：方案 A**——PepperLib 已推送至 `github.com/nullpepper/PepperLib`（public，
  插件 CI 免 token checkout）。
- 插件 CI 的 `checkout nullpepper/PepperLib → publishToMavenLocal` 步骤生效；
  后续 lib 迭代由插件 CI 自动构建发布。
- **验证（2026-08）**：三仓库 GitHub Actions 全绿——lib `build`（test+spotless+javadoc）
  通过；Claim（build+spotlessCheck+spotbugs）通过；Union（build+spotbugs+MariaDB 集成）通过。
- 备选 B（`PEPPER_MAVEN_URL` 内部仓库）/ C（composite build）不采用。

## 5. 无单测菜单的迁移验证（已实施，知悉）

AuditMenu / InviteMenu / ConfigMenu / GiveMemberMenu / RankAssignMenu / AcceptInviteMenu /
FindGuildMenu 无行为单测；迁移靠逐行等价转换 + 协议层测试（PageHolderAdapterTest /
GuiManagerOpenPageTest / ConfirmMenuBehaviorTest / PagedMenuSupportTest）覆盖事件链路。
建议后续补 MockBukkit 抽测（计划风险清单已列）。

## 6. 正式发布目标（**用户决定：暂不发布**）

- **用户决策（2026-08）：暂不发布、暂不考虑**。
- 0.1.0 保持 mavenLocal（本地/CI 经 publishToMavenLocal 可用）；远程发布（内部仓库 /
  JitPack / Maven Central）挂起，待需要时再定。

## 7. 阶段 6 验收核对（已实施）

- ✅ GuiManager 不再向页面暴露完整 Bukkit 事件（全部经 GuiClick）
- ✅ GuiSessionId 打开生成 / 关闭失效（测试覆盖）
- ✅ 插件禁用 / 玩家退出 / 顶部点击 / 拖拽行为不变（onDisable/onPlayerQuit 路径逐条核对）
- ✅ 15 个菜单全部迁移，AbstractGui / PagedMenu 已删除，PageGuide 已去事件化
- ⚠️ 行为等价依赖代码转换核对（见第 5 条）

## 8. 第二轮架构统一（已实施 2026-08）

用户决策：统一 ThreadGuard 与 GUI 事件管线。

1. **ThreadGuard → lib**（`io.pepper.lib.task.ThreadGuard`）：合并两插件守卫为超集
   （Async 断言 + 带上下文重载 + 主线程标记 IO 断言）。本地实现删除，测试迁移。
2. **GUI 事件管线 → lib**（`io.pepper.lib.gui.GuiHost` + `GuiHolder`）：
   - GuiHost 为**实例类**（两插件可同服共存，各持实例；非静态单例）；
   - Union `GuiManager` 变 30 行薄壳（静态入口 inst() 委托）；
   - Claim 删除 `ClaimGuiListener`，`ClaimGui.Holder` 实现 lib GuiHolder，
     openXxx 改经 `guiHost.openGui`（打开调度 + 连点防抖为行为增强）；
   - `PageGuiContext`/`PageHolderAdapter` 随迁 lib；
   - 顺带修复原 GuiManager 空 holder 点击 NPE（`gui != null` 守卫）。

验证：lib 109 / Claim 544 / Union 314 全绿（含 spotless/spotbugs/javadoc）。
提交：lib（ThreadGuard + GuiHost）、claim `246116e`、union（GuiManager 薄壳）。

## 9. 统一 i18n 机制（已实施 2026-08）

设计文档：`docs/i18n-unified-design.md`。机制入 lib（`io.pepper.lib.i18n`：LanguageBundle /
TextValue / LocaleResolver / PlaceholderResolver / PapiPlaceholderResolver），两插件
LanguageManager 瘦身为壳（format/render 等调用点零改动）。

1. **评估修正（实施中发现）**：评估时认为 Claim 递归翻译「无调用点依赖可废弃」——
   实测 `PlayerMiscCommands` 以 `pclaim.auto` / `claim.help.auto` **键值**渲染 help 条目，
   递归翻译是活跃生产语义 → **保留为 Claim 壳层策略**（depth<3 同旧逻辑，raw 与 render
   双路径）；lib 保持严格语义（与 Union「值不重译」一致）。
2. **设计修正（评审）**：bundled→磁盘首启复制**否决**——首启全键快照会在插件更新后
   遮蔽 bundled 新文本（Claim 旧版已有此陷阱，Union 现状无此问题）；保持纯 bundled +
   可选磁盘逐键覆盖，FallbackLanguage 降级路径因此保持纯 bundled 契约。
3. **PAPI 机制入 lib**：`compileOnly me.clip:placeholderapi:2.11.6` 软依赖（守卫 +
   惰性类加载 + 异常兜底，Union 生产已验证模式）；**启用是插件策略**——Union 壳一行
   装配 `PapiPlaceholderResolver.INSTANCE`（`papi/PapiSupport.java` 删除，净删 33 行），
   Claim 壳不启用（现状无 PAPI，行为零变化）。
4. 统一语义：两阶段渲染（模板预解析缓存，按 locale 分桶）、回退链
   L → 默认 → 回退 → 键名、`TextValue.literal/mini`（mini 剥离点击事件 + 插入文本）、
   原子 reload、坏 YAML/坏模板降级；Union `formatText` 移除（零调用点）。

验证：lib 23 / Claim 546 / Union 314 全绿（含 spotless/spotbugs/javadoc）。

## 10. 第二轮提取 A+B：Scheduler 与 Amounts 统一（已实施 2026-08）

依据 docs/extraction-audit-2.md（审计）与 docs/extraction-a2-plan.md（方案）。

1. **Scheduler → lib**（`io.pepper.lib.task`）：
   - `PepperScheduler` 增 3 个遗留别名 default 方法（`runTaskAsynchronously → runAsync`、
     `runTaskTimer → runRepeating`、`supplyOnMainThread → supplyOnMain`）——委托方向翻转
     （旧插件接口是「新名 default 委托旧名」，lib 统一为「旧名 default 委托规范名」），
     存量调用名全部保留；
   - 新 `BukkitPepperScheduler`（两份 PaperScheduler 实现 diff 仅差 package，合并为一）；
   - 两插件删除 Scheduler/PaperScheduler/SchedulerPepperContractTest 共 6 文件，
     55 个引用文件机械替换类型（方法名零改动）；测试替身改实现 lib 接口并补 isMainThread。
2. **Amounts → lib**（`io.pepper.lib.money.Amounts`）：
   - 超集：`toCents(double/BigDecimal)`（Claim HALF_UP）、`toMajor(long)`（Union 2^53 守卫，
     取代 toVault）、`format(long)` 去尾零（Union）+ `formatFixed(long)` 固定 2 位（Claim）、
     `tryParse`（Union）、`isValid(long)` ±1e15 + 参数化 `isValid(long, long)`；
   - 两插件 util/Amounts 删除；Claim 4 处 format → formatFixed（显示逐字符不变），
     Union 37 处 format 同名收敛，54 处调用点全部有映射。
3. 验证：lib 150 / Claim 538 / Union 308 全绿（spotless/spotbugs/javadoc 过门）；
   显示语义零变化由 lib 双 format 变体测试固化。

## 11. 第二轮提取 C+D：Vault 解析与 PAPI 注册样板（已实施 2026-08）

依据 docs/extraction-audit-2.md C/D 项。

1. **Vault 软依赖解析 → lib**（`io.pepper.lib.economy.VaultSupport`）：惰性
   `economy()`（ServicesManager 查询，重载安全）；两桥内部解析委托（Claim 构造时 /
   Union 每调用），桥本体契约（cents+Result vs double+boolean）不统一。
   新增 jitpack 仓库 + VaultAPI 1.7 compileOnly/testImplementation（与 Union 同坐标）。
2. **PAPI 扩展注册样板 → lib**（`io.pepper.lib.papi.PapiExpansionSupport`）：
   泛型 Supplier 形态 `register(factory)`——守卫（未安装/未启用 → null）在 lib 内，
   **工厂仅在 PAPI 在场时调用**（扩展类 extends PAPI 类型，无 PAPI 时构造即
   NoClassDefFoundError）；返回注册实例供 onDisable 注销；异常兜底。
   Claim/Union 注册点改一行；Union 保留实例引用注销不变。
3. **实施发现**：Claim 测试运行时缺 PAPI jar——`PapiExpansionSupport.register` 的
   泛型边界使 Mockito 重变换 `PepperClaimPlugin` 时解析 `PlaceholderExpansion` 签名
   → ClassNotFoundException → 补 testImplementation（与 Union 对齐）。
4. 验证：lib 155 / Claim 538 / Union 308 全绿（spotless/spotbugs/javadoc 过门）。
