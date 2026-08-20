# PepperLib 第二轮提取审计（2026-08）

> 目标：在 ThreadGuard/GuiHost/i18n 统一之后，找出两插件剩余的、可抽象进 PepperLib 的代码。
> 纪律：双消费者 → 机制进 lib，策略留插件；单消费者不提取。
> 方法：全量源码树对比（Claim 196 类 / Union 202 类），对同名同构候选逐一 diff 核实。

## 候选汇总（按优先级）

| # | 候选 | 证据 | 价值 | 风险 | 建议 |
|---|---|---|---|---|---|
| A | **Scheduler 统一** | 三副本同构（lib PepperScheduler + Claim extends + Union 独立接口）；两份 PaperScheduler **仅差 package 与一行注释**；44 个文件引用插件 Scheduler 类型 | 高（消 2 接口 + 2 实现 ~300 行） | 低-中（机械类型替换，方法名不变） | **推荐做** |
| B | **Amounts 金额工具** | 同名同构（分↔元换算/格式化/校验），语义已漂移（显示位数、上下界不同）；Claim 6 处 / Union 48 处调用 | 中-高（同一概念两份实现，防再漂移） | 低（纯函数） | **推荐做** |
| C | **Vault 软依赖解析** | 两桥各自实现「getPlugin + ServicesManager 守卫」；桥本体契约不同（cents+Result vs double+boolean，不可统一） | 低-中（~15 行重复） | 低 | 可选（小件） |
| D | **PAPI 扩展注册样板** | 两份 PlaceholderExpansion 子类样板（identifier/author/version/hashCode/注册守卫 ~40 行）；onRequest 留插件 | 中（样板去重 + 注册安全一致） | 低 | 可选 |
| E | **主线程事件分发** | Union EventBus 仅测试缝（直呼 callEvent）；Claim ClaimEventBus 是完整机制（主线程分发 + Pre 同步可取消 + Post fire-and-forget） | 中（Claim 形态更正确，Union 可受益） | 中（耦合 Claim 写入管线） | 需设计评审 |
| F | UUIDv7 / PlayerHeads / ResultRenderer / TargetResolver / Cooldown / ServiceSupport / StorageExecutor | 均为单消费者或领域机制不同 | — | — | **不做** |

## A. Scheduler 统一（详情）

现状：
- lib `PepperScheduler`：`runTask / runAsync / runRepeating / supplyOnMain / isMainThread`；
- Claim `Scheduler extends PepperScheduler`：补遗留别名 `runTaskAsynchronously / runTaskTimer / supplyOnMainThread`（default 委托新名）；
- Union `Scheduler`：独立同构接口（同样的新方法 + 同样 4 个别名）；
- 两份 `PaperScheduler` 实现 diff 后**仅差 package 与一行注释**。

提取方案：
1. lib `PepperScheduler` 增加 3 个 default 别名方法（`runTaskAsynchronously → runAsync`、`runTaskTimer → runRepeating`、`supplyOnMainThread → supplyOnMain`）；
2. 两插件删除 `Scheduler` + `PaperScheduler`（4 个文件）；
3. 44 个引用文件机械替换类型（`io.pepper.claim/union.scheduler.Scheduler` → `io.pepper.lib.task.PepperScheduler`；注入点改 `new PepperSchedulerImpl` 或 lib 提供 `PaperScheduler` 实现——**lib 需新增一个基于 Bukkit 的 PepperScheduler 实现**（现两份实现并入 lib，如 `io.pepper.lib.task.BukkitPepperScheduler`））；
4. 测试替身（ImmediateScheduler 等）改 implements lib 接口。

行为：零变化（别名委托语义与现状一致）。工作量：lib ~1 小时；每插件机械替换 ~0.5 天。

## B. Amounts 统一（详情）

现状（已 diff）：
| 方法 | Claim | Union | 差异 |
|---|---|---|---|
| 分→元 | `toMajor(long)`（BigDecimal 2 位 HALF_UP） | `toVault(long)`（/100.0 + 2^53 守卫） | 守卫更严在 Union |
| 元→分 | `toCents(double/BigDecimal)` | 无 | Claim 独有 |
| 显示 | `format(long)` 固定 2 位小数 | `format(long)` 去尾零 | **显示语义不同** |
| 校验 | `isValid(long)`：0..9e15 | `isValidAmount(long)`：±1e15 | 上下界不同 |
| 解析 | 无 | `tryParse(String)`（拒科学计数法/超 2 位小数/超界） | Union 独有 |

提取方案（lib `io.pepper.lib.money.Amounts`，超集）：
- `toCents(double)` / `toCents(BigDecimal)` / `toMajor(long)`（带 2^53 守卫）/ `tryParse(String)` / `isValid(long, long maxAbs)`；
- `format(long)`（去尾零，Union 语义）+ `formatFixed(long)`（固定 2 位，Claim 语义）；
- 插件收敛：Claim 6 处调用 → `formatFixed`（显示零变化）；Union 48 处 → `format`/`toMajor`（零变化）；`isValid` 边界经参数化保留各域策略。

## C. Vault 软依赖解析（可选）

lib `io.pepper.lib.economy.VaultSupport`：`@Nullable Economy economy()`（getPlugin 守卫 + ServicesManager 惰性解析，取 Union 惰性形态）。两桥内部 5 行替换。桥本体（接口契约）不统一。

## D. PAPI 扩展注册样板（可选）

lib `io.pepper.lib.papi.PapiExpansionSupport`：`boolean register(JavaPlugin, PlaceholderExpansion)`（未安装/未启用守卫 + 异常兜底，与 PapiPlaceholderResolver 同族）；`onRequest` 与元数据仍留插件。与刚入 lib 的 PAPI 机制形成完整两面（解析 + 注册）。

## E. 主线程事件分发（需设计评审）

lib `io.pepper.lib.event.MainThreadEventBus(PepperScheduler, Dispatcher)`：`callSync`（主线程同步可取消）/ `fireAndForget`。Claim `ClaimEventBus` 基于它重构（Pre/Post 语义留插件）；Union `EventBus` 可选用（现仅为测试缝，直呼 callEvent 在服务线程触发事件有主线程隐患——引入后可顺带修正）。
风险：Claim 事件总线耦合写入管线（ServiceSupport），迁移需回归全部事件链路测试。

## F. 不做（证据）

- `Uuids`（UUIDv7）：Claim 单消费者；Union 无对应实现 → 暂缓（若未来统一 id 体系再提）。
- `PlayerHeads`：Union 单消费者（Claim GUI 无头颅物品）。
- `ResultRenderer` 反馈分级：Claim 单消费者（Union 反馈仅 sendMessage 级）。
- `TargetResolver` / `CooldownSpec` / `ConfirmIntent` / `PendingAction`：领域语义；核心（ConfirmRegistry/ConfirmEntry）已入 lib。
- `ServiceSupport`：两插件骨架机制不同（pipeline+事件总线 vs TaskExecutor+队列+stage）。
- `StorageExecutor`：Union 专属（Claim 走 pipeline 异步，机制不同）。
- `ClaimResult/UnionResult` + `ResultCode`：领域契约（键域/语义不同），统一收益低风险高。

## 结论

**A（Scheduler）+ B（Amounts）推荐实施**——证据最硬（同构 diff + 使用面大）、风险最低。
C/D 为可选小件（各 ~15-40 行样板去重）；E 需先评审 Claim 事件总线耦合面。
F 保持现状。
