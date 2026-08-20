# PepperLib 设计合理性评审

> 评审日期：2026-08-20。基线：PepperLib 0.5.0（commit `afa6ad9`）。
> 触发：全家族 PepperLib 版本升级（Union/Claim 0.2.0 → 0.5.0；BindManager 已在 0.5.0）后，
> 对库的整体设计做一次独立判断。前序材料：`pepperlib-suitability-review.md`（0.1.0 通用性审查）、
> `pepperlib-dual-loading-and-consumer-migration.md`（双模式方案）、`adoption-decisions.md`（决策记录）、README、CHANGELOG。

## 1. 结论摘要

**总体：设计合理，且明显高于同类 Bukkit 插件家族的平均水平。**
双模式加载、版本化运行时服务、japicmp 二进制兼容门、按「两消费者一致语义」的提取纪律，
构成了一个自洽且有真实验证的共享库架构；0.1.0 审查指出的死代码问题已在 0.5.0 收敛。
**不合理/偏弱之处集中在三处**：① 消费者侧 `REQUIRED_PEPPERLIB_API` 常量靠复制粘贴维护，
本次升级正是其失效的实证（Union/Claim 停留在 "0.2"，若服务器装 0.5.0 前置插件，
两插件启动时会被自己的校验禁用）；② README 定位表述未随 0.5.0 的
「三插件共享」事实更新；③ CHANGELOG 缺 0.3.0 条目。详见 §4。

## 2. 设计事实盘点（0.5.0）

### 2.1 架构骨架

| 设计点 | 实现 | 状态 |
|---|---|---|
| 运行形态 | 双模式：前置插件（`PepperLib.jar`，未 relocate 单一实例）+ shade 模式（消费者 relocate 私有命名空间） | 已实施（0.2.0），两种模式各有真实 Paper smoke 验证 |
| 运行时契约 | `PepperLibRuntime`（ServicesManager 注册）：`apiVersion()` + `supports(capability)` | 已实施（0.2.0） |
| 消费者接入 | `compileOnly` 坐标依赖 + `plugin.yml depend` + onEnable 版本校验 | Union / Claim / BindManager 三插件一致 |
| 能力自适应 | `@MinMinecraftVersion` 类级注解 → classpath 扫描 → 注册表决策；`supports("gui-host")` | 已实施（0.4.0），守卫测试防注解漂移 |
| 二进制兼容 | japicmp 对上一发布版本，纳入 `./gradlew check` 绿门 | 已实施（0.2.0，0.5.0 起基线 0.4.0） |
| 版本策略 | 0.1.x 纯修复 / 0.2.x 可增 API / 1.0 冻结；根项目 version 单一来源 | 已实施 |
| 构建 | buildSrc 约定插件 + `libs.versions.toml` 版本目录；`BuildInfraGuardTest` 防硬编码 | 已实施（0.4.0） |
| 依赖纪律 | 零插件引用（`SourceDependencyGuardTest` 守卫）；Bukkit/PAPI/Vault 仅 compileOnly；纯 JDK 模块（expression/util/storage/money/validation） | 已实施 |
| 状态隔离 | `ThreadGuard.Instance` / `GuiHost` 实例类——同服多插件不共享静态状态 | 已实施（0.2.0 / 二轮统一） |
| 发布 | mavenLocal + CI `publishToMavenLocal`；远程发布按用户决策暂缓 | 已实施（决策 #6） |

### 2.2 演进路径（0.1.0 → 0.5.0）

- 0.1.0：从 Union/Claim 提取首批共享（task/storage/gui/confirm/validation/i18n/money/economy/papi）。
- 0.2.0：双模式加载 + `PepperLibRuntime` + japicmp 门 + CHANGELOG/README。
- 0.3.0：BindManager 前置模式接入（mavenLocal 有产物，**CHANGELOG 无条目**）。
- 0.4.0：从 BindManager 提取 `OneTimeCodeService` / JDBC 工具；自适应加载 + 注解能力决策。
- 0.5.0：从 CustomMessage 提取 `SafeExpression` / `PlaceholderVariableMapper` / `CooldownTracker` / `Hashing`。

各代均由 japicmp 保证对上一代二进制兼容；本次 0.2.0 → 0.5.0 的跨代升级因此是纯增量，
Union/Claim 编译/测试全绿（本日验证：Union 23s / Claim 10s，BUILD SUCCESSFUL）。

## 3. 合理之处（逐项判断）

1. **双模式加载解决的是真实部署约束，不是炫技。**
   前置模式（单一实例）消除「每个插件各带一份 io.pepper.lib 导致类加载器间类型不互通」的
   经典陷阱；shade 模式为第三方消费者保留零前置的自包含路径。两种模式在同一个 JAR 中
   互斥的约束写进了方案文档，README 亦声明——契约清晰。
2. **版本化运行时服务 + 消费者显式校验是正确的主从契约。**
   `apiVersion()` 与发布坐标同源（根项目 version 注入 plugin.yml），消费者 `startsWith` 前缀
   校验把「服务器装了不匹配的 lib」从「运行期随机 NoSuchMethodError」变成「启动期明确禁用
   并给出升级建议」。对 0.x 阶段（前缀语义 = minor 契约）足够。
3. **japicmp 进绿门是本次跨代升级顺利的直接原因。**
   没有它，0.2.0 → 0.5.0 的「放心升」不成立；它是整个「不断提取」策略的安全网。
4. **提取纪律（两消费者一致语义才提取）有效抑制了过度抽象。**
   0.1.0 审查时 55% API 零消费者的教训（`GuiItemFactory` 至今 Experimental 未转正）被
   后续提取严格执行：0.4.0 提取物（验证码/JDBC）与 0.5.0 提取物（表达式/冷却/哈希）均为
   纯 JDK、测试随迁、语义逐条对照。
5. **状态隔离设计（`ThreadGuard.Instance` / `GuiHost` 实例类）预见了多插件同服共享
   类加载的静态污染问题**——这是共享库最常见的隐性坑，设计上正面处理了。
6. **自适应加载（`@MinMinecraftVersion` 注解 + 扫描）把「能力 → 最低版本」的映射
   从手写分支变成声明式**，且用守卫测试（`MinMinecraftVersionGuardTest`）防止注解与
   类签名漂移——复杂度有边界、有测试兜底。
7. **构建治理（版本目录 + buildSrc + 单一版本源 + 防硬编码守卫）**使版本升级成为
   「改一处」而非「搜全仓」——本次升级 Union/Claim 仅各改 2 行依赖 + 1 行常量即完成，
   是这套治理的直接收益。

## 4. 不合理 / 偏弱之处（按影响排序）

### 4.1 【实证缺陷】消费者侧版本常量靠复制粘贴，已实际漂移

- Union/Claim 的 `REQUIRED_PEPPERLIB_API = "0.2"` 在 0.3.0/0.4.0/0.5.0 三代发布中
  均未被同步（BindManager 同步过，Union/Claim 没有）。
- 后果：服务器若安装 0.5.0 前置插件，Union/Claim 启动时 `startsWith("0.2")` 失败 →
  **插件被自己的校验禁用**。这是本次升级修复的真实故障模式，也是「设计上应该避免」的。
- 根因：版本契约分散在 N 个消费者主类里，无单一来源、无构建期校验。
- 建议（三选一，按成本排序）：
  1. **库内提供契约常量**（如 `PepperLibRuntime.API_VERSION`），消费者校验直接引用
     （仍可能拷错，但至少无需知道「最新是多少」）；
  2. **构建期校验**：消费者 Gradle 任务断言「已解析的 pepper-lib 版本前缀」与
     `REQUIRED_PEPPERLIB_API` 一致（改动最小，防再犯）；
  3. **运行时改为「≥」语义**：`apiVersion()` 提供可比较版本对象（`ServerVersions` 已有
     比较纯函数），消费者校验「>= 声明的最低版本」而非「startsWith 精确前缀」——
     0.x 阶段前缀语义可保留，但 1.0 之后必须切换（见 4.3）。

### 4.2 定位表述与事实脱节（文档债）

- README 定位仍写「PepperUnion 与 PepperClaim 共享」，但 0.5.0 内容已含
  「源自 PepperBotCustomMessage 提取」的组件——实际生态是「Union / Claim / BindManager
  已接入 + CustomMessage 已提取待接入」。提取时方案文档已标注「定位变更需用户确认」，
  但 README 未同步。
- 影响：第三方读者对「什么能进 lib」的边界产生误判（单消费者组件是否允许提取？）。
- 建议：README 定位段更新为「Pepper 插件家族共享库（Union/Claim/BindManager 已接入，
  CustomMessage 提取中）」，并明确单消费者提取的准入条件（如「高价值安全原语，预期
  第二消费者」）——`SafeExpression` 的提取其实是该规则的例外，应显式记录。

### 4.3 版本校验语义在 1.0 之后不成立

- `startsWith("0.5")` 在 0.x 阶段近似「minor 契约」；但 1.0 冻结后，1.x → 2.x 的
  破坏性升级若消费者仍写 `startsWith("1")` 会错误放行。README 已声明 1.0 冻结 API，
  但校验机制未配套演进（前缀 → 比较语义）。
- 建议：1.0 发布前把校验统一为「最低版本比较」（`ServerVersions` 已有实现基础），
  消除前缀 hack。

### 4.4 小项（不阻塞）

- CHANGELOG 无 0.3.0 条目（mavenLocal 有产物）——发布史不完整，补记。
- `GuiItemFactory` 自 0.1.0 起 Experimental 至今无消费者——1.0 前需决策（接入或删除）。
- 前置模式样板（depend 声明 + onEnable 校验 + 常量）在每个消费者重复约 20 行——
  可接受（插件主类本就各异），不建议为此引入基类抽象。

## 5. 对「通用库」定位的判断

延续 0.1.0 审查结论：**作为家族内部共享库：成立且健康；作为对外通用库：仍不满足**——
远程发布未落地（用户决策暂缓）、`GuiItemFactory` 未收敛、第三方消费者为零。
当前定位与用户决策一致，无设计冲突。

## 6. 一句话结论

**PepperLib 的设计骨架（双模式 + 版本化运行时 + japicmp + 提取纪律 + 状态隔离）合理且被
真实演进验证；主要短板是消费者侧版本契约的维护方式（本次升级修复了一个因它而起的
潜在启动禁用故障）与少量文档/遗留项。建议优先落实 §4.1 的构建期校验或库内常量，
并在 1.0 前完成 §4.2/§4.3/§4.4。**
