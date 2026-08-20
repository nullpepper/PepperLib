# PepperLib 第二轮提取实施方案（A: Scheduler 统一 + B: Amounts 统一）

> 依据 docs/extraction-audit-2.md 候选 A/B；纪律：机制进 lib，策略留插件，TDD 红绿。
> 行为零变化是硬约束：所有存量调用名与方法语义在合并后保持不变。

## 0. 目标与验收标准

**目标**：两插件的 `scheduler/Scheduler` + `scheduler/PaperScheduler` 与 `util/Amounts` 删除，
机制并入 PepperLib（`PepperScheduler` 遗留别名 + `BukkitPepperScheduler` + `io.pepper.lib.money.Amounts`），
三仓库全绿。

**验收**：
1. lib `PepperScheduler` 新增 3 个 legacy 别名 default 方法，别名→规范名委托契约测试绿；
2. lib `BukkitPepperScheduler`（mockbukkit 测试：主线程判定 / runTask / runAsync / runRepeating / supplyOnMain）绿；
3. lib `io.pepper.lib.money.Amounts` 超集测试绿（Union AmountsTest + Claim AmountsTest 断言并集）；
4. Claim/Union 的 scheduler 包与 util/Amounts 删除；全部引用文件类型替换后两插件全量测试绿；
5. 显示语义零变化（Claim 4 处 `formatFixed` 保持 2 位小数；Union 37 处 `format` 保持去尾零）；
6. 三仓库提交推送（lib → main、Claim → main、Union → master）。

---

## 1. A 部分：Scheduler 统一

### 1.1 现状（已核实证据）

| 项 | 证据 |
|---|---|
| lib `PepperScheduler` | 5 个抽象方法：`isMainThread / runTask / runAsync / runRepeating / supplyOnMain`；javadoc 明言「由插件各自实现适配器」 |
| Claim `Scheduler` | `extends PepperScheduler`，补旧名 `runTaskAsynchronously / runTaskTimer / supplyOnMainThread`，新名 default 委托旧名；`isMainThread` 覆盖为 default true（测试替身友好） |
| Union `Scheduler` | 独立同构接口（同样新名 default 委托旧名） |
| `PaperScheduler` 实现 | 两份 **diff 仅差 package 与一行注释**；实现旧名，Bukkit 调用一一对应 |
| 注入点 | 各 1 处：`PepperClaimPlugin:81` / `PepperUnionPlugin:102`（字段初始化 `new PaperScheduler(this)`） |
| 引用面 | 44 个文件引用插件 Scheduler 类型 |
| 测试替身 | 6 文件：Claim `SchedulerPepperContractTest`；Union `SchedulerPepperContractTest` / `TestScheduler` / `PageHolderAdapterTest` / `CrossGuildCoordinatorTest` / `GuildCommandQueueTest` |
| 委托方向契约 | 两插件 `SchedulerPepperContractTest.newContractNamesDelegateToLegacyNames` 固化「新名 → 旧名」委托 |

### 1.2 lib 改动（TDD 红绿）

1. **`PepperScheduler` 加 3 个 legacy 别名 default 方法**（方向翻转：旧名 → 规范名委托）：
   - `default void runTaskAsynchronously(Runnable task) { runAsync(task); }`
   - `default void runTaskTimer(Runnable task, long delayTicks, long periodTicks) { runRepeating(task, delayTicks, periodTicks); }`
   - `default <T> CompletableFuture<T> supplyOnMainThread(Supplier<T> supplier) { return supplyOnMain(supplier); }`
   - 规范名保持抽象（lib 原有 API 不变）；`runTask`/`isMainThread` 两边同名无需别名。
2. **新增 `io.pepper.lib.task.BukkitPepperScheduler implements PepperScheduler`**：移植双份 PaperScheduler
   （构造 `JavaPlugin`；`isMainThread → Bukkit.isPrimaryThread()`；`runTask → runTask`；
   `runAsync → runTaskAsynchronously`；`runRepeating → runTaskTimer`；`supplyOnMain → supplyAsync(runner)`）。
3. **新测试**：
   - `PepperSchedulerAliasContractTest`：记录式替身（只实现规范名）→ 调旧名 → 断言委托到规范名
     （替代两插件同名契约测试，方向翻转后语义：旧名可用且行为一致）；
   - `BukkitPepperSchedulerTest`（mockbukkit）：主线程判定真假、runTask 执行、runAsync 不阻塞主线程、
     runRepeating 周期执行（短暂等待或注入调度器桩）、supplyOnMain 返回结果。
   - lib javadoc 同步更新（删除「由插件各自实现适配器」表述）。

### 1.3 插件迁移（机械替换，方法名零改动）

1. 删除 Claim/Union `scheduler/Scheduler.java` + `scheduler/PaperScheduler.java`（4 文件）；
2. 插件主类字段：`private final PepperScheduler scheduler = new BukkitPepperScheduler(this);`
   （import `io.pepper.lib.task.*`）；
3. 44 个引用文件：import 与类型 `Scheduler` → `io.pepper.lib.task.PepperScheduler`
   （字段/参数/局部变量；方法调用名一律不变——旧名经 lib default 生效）；
4. 测试替身（6 文件）：改 `implements PepperScheduler`，实现规范名（旧名实现删除）；
   `TestScheduler`（Union）语义（蹦床重入）不变，仅换接口；
5. 两插件 `SchedulerPepperContractTest` 删除（契约已入 lib `PepperSchedulerAliasContractTest`）。

### 1.4 行为等价论证

- 全部存量调用名（新旧两套）在合并接口上仍可用；旧名经 default 委托到规范名，
  Bukkit 调度调用点一一对应（`runTaskAsynchronously` ↔ `runAsync` 等）；
- `isMainThread` 语义不变（生产 = Bukkit.isPrimaryThread，测试替身自实现）；
- 测试替身行为（立即执行 / 蹦床重入）不变，仅接口类型更换；
- 编译期兜底：44 文件全量编译通过即证明无遗漏引用。

---

## 2. B 部分：Amounts 统一

### 2.1 现状（已核实证据）

| 方法 | Claim | Union | 差异 |
|---|---|---|---|
| 分→元 | `toMajor(long)`（BigDecimal 2 位 HALF_UP） | `toVault(long)`（/100.0 + 2^53 守卫） | 守卫在 Union |
| 元→分 | `toCents(double)` / `toCents(BigDecimal)`（HALF_UP） | 无 | Claim 独有（仅测试使用） |
| 显示 | `format(long)` 固定 2 位 | `format(long)` 去尾零 + `format(double)`（NaN→"0"） | **显示语义不同** |
| 校验 | `isValid(long)`：0..9e15（仅测试使用） | `isValidAmount(long)`：±1e15 | 上下界/符号语义不同 |
| 解析 | 无 | `tryParse(String)`（拒科学计数法/超 2 位小数/超界） | Union 独有 |
| 常量 | 无 | `MAX_CENTS=1e15` / `MAX_AMOUNT=1e15` | Union 独有 |
| 调用点 | 6 处（main：format×4、toMajor×2） | 48 处（format×37、isValidAmount×5、toVault×4、tryParse×4 + 常量） | — |

### 2.2 lib API（`io.pepper.lib.money.Amounts`，超集）

```java
public final class Amounts {
    public static final long MAX_CENTS = 1_000_000_000_000_000L;   // Union 语义
    public static final double MAX_AMOUNT = 1e15;                  // Union 语义

    public static long toCents(double major);        // Claim HALF_UP 语义
    public static long toCents(BigDecimal major);    // Claim 语义
    public static double toMajor(long cents);        // Union 语义（2^53 守卫，取代 toVault）
    public static String format(long cents);         // Union 语义（去尾零）
    public static String formatFixed(long cents);    // Claim 语义（固定 2 位小数）
    public static String format(double amount);      // Union 语义（NaN/Inf → "0"）
    public static Optional<Long> tryParse(String raw);// Union 语义
    public static boolean isValid(long cents);       // Union 语义（±MAX_CENTS）
    public static boolean isValid(long cents, long maxAbsCents); // 参数化（域策略留插件）
}
```

### 2.3 收敛映射（逐调用点）

| 位置 | 旧调用 | 新调用 | 行为 |
|---|---|---|---|
| Claim `VaultEconomyBridge` ×2 | `Amounts.toMajor(cents)` | lib `toMajor(cents)` | 相同（整数分，2^53 内无损） |
| Claim `SlotService` ×2 / `QuotaService` ×2 | `Amounts.format(long)` | lib `formatFixed(long)` | **显示不变**（100.50） |
| Union 37 处 | `Amounts.format(long/double)` | lib `format(long/double)` | **显示不变**（去尾零） |
| Union 4 处 | `Amounts.toVault(cents)` | lib `toMajor(cents)` | 相同（同名守卫） |
| Union 5 处 | `Amounts.isValidAmount(cents)` | lib `isValid(cents)` | 相同（±1e15） |
| Union 4 处 | `Amounts.tryParse(raw)` | lib `tryParse(raw)` | 相同 |
| Union 常量引用 | `Amounts.MAX_CENTS/MAX_AMOUNT` | lib 常量 | 相同 |
| Claim `AmountsTest` | — | 删除，断言并入 lib 测试 | 语义归一（isValid 符号语义取 lib 参数化形态） |
| Union `AmountsTest` | — | 删除，断言并入 lib 测试 | — |

### 2.4 lib 测试 = 两插件断言并集

`io.pepper.lib.money.AmountsTest`：Union 全套（format 去尾零/负数、tryParse 拒科学计数法与超界、
isValid ±1e15、toMajor 2^53 守卫抛异常）+ Claim 全套（toCents HALF_UP 舍入 1.235→124、
toCents(BigDecimal)、formatFixed "100.50"/"0.00"）+ 参数化 isValid(long,long) 边界。

### 2.5 显示语义保持论证

- Claim 生产调用仅 4 处 `format` → `formatFixed`：输出字符串逐字符不变（测试固化 "100.50"）；
- Union 37 处 `format` → lib `format`：语义逐字相同（去尾零、NaN→"0"）；
- 归一化只发生在**无生产调用**的语义上（Claim isValid/toCents 仅测试使用）。

---

## 3. 实施顺序与工作量

| 步骤 | 内容 | 工作量 |
|---|---|---|
| 1 | **B**：lib Amounts 测试（红）→ 实现（绿）→ spotless/javadoc | 0.5 天 |
| 2 | B：两插件映射替换 + 删旧类 + 全量回归 | 0.5 天 |
| 3 | **A**：lib 别名 + BukkitPepperScheduler + 测试（红→绿） | 0.5 天 |
| 4 | A：两插件删除 4 文件 + 44 文件类型替换 + 替身迁移 + 全量回归 | 0.5-1 天 |
| 5 | 三仓库 build 绿门（spotless/spotbugs/javadoc）+ 提交推送 + decisions 追加 | 0.5 天 |

**风险与兜底**：
- A 机械替换遗漏 → 编译期全量兜底（漏一个文件就编译失败）；
- B 显示语义 → 映射表逐点核对 + lib 测试固化两个 format 变体；
- 委托方向翻转（新名→旧名 变为 旧名→新名）→ `PepperSchedulerAliasContractTest` 固化新方向，
  行为等价（Bukkit 调用点不变）。

## 4. 非目标

- 不统一 Vault 桥契约（cents+Result vs double+boolean 是领域契约）、EventBus、Result 类型等
  （见 extraction-audit-2.md C/E/F，另行决策）；
- 不引入配置化显示格式（format/formatFixed 两态即为终态）。
