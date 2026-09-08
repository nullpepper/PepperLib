# YAML 配置系统统一设计（下沉 PepperLib）

> 状态：待评审（开放问题见 §14；OQ-2 已裁决——支持注释保留写回，本版已并入）
> 关联先例：`persistent-store-extraction.md`（0.8.0 persist 下沉）、`i18n-unified-design.md`（i18n 统一）
> 本文只描述设计，不含实现；实现按 §11 分期 + 仓库 TDD 纪律落地。

## 1. 背景与驱动

Pepper 系插件目前有 **四种互不相通的 YAML 配置技术**，同一家族内重复建设且语义各自漂移：

| 现状 | 位置 | 问题 |
|---|---|---|
| 手写 YAML 子集解析器（285 行） | PepperTreeCut `config/Yaml.java` | 非标准 YAML：不支持嵌套块序列/多行标量/转义/锚点；错误文案中文但无行列；`key:` 空值语义与标准不同（空 Map vs null）；维护成本在消费方 |
| 裸 `new Yaml().load()` | PepperLib `i18n/LanguageBundle`（130/140 行） | snakeyaml **隐式依赖**（靠 paper-api 传递编译，无版本声明）；安全/语义策略（SafeConstructor、timestamp、重复键、null）散落在调用点 |
| Bukkit YamlConfiguration | PepperClaim（config/ 下多文件 + 程序化 `disk.save`）、PepperUnion（PluginSettings 手工 getter 委托）、GlowingSquad、PepperMinecart、PepperTrashBin、UserPrefix | 对象往返写回必毁注释；点号路径语义（`a.b` 键 vs 嵌套）是历史坑源；解析层被 Bukkit 绑定，无法 JVM 单测 |
| — | PepperPvpArena `ArenaMapLoader` | 地图文件类，边界见 §2 非目标 |

驱动事件：PepperTreeCut 欲退役自研解析器（上轮评估结论：SnakeYAML 封装收益明确、坑集中在 5 个语义点、可测可锁）；用户裁决：**下沉 PepperLib，后续所有基于 PepperLib 且需要配置文件的插件统一使用新 YAML 配置系统**；并裁决：**写回能力必须支持，且不损失注释**（OQ-2）。

额外事实（支撑下沉）：PepperLib 0.9.0 的 LanguageBundle 已构成运行期依赖服务端捆绑 snakeyaml 的先例——下沉**不引入新依赖面**，而是把隐式依赖显式化、策略单一来源化。注释保留写回已在服务端捆绑 snakeyaml-2.6.jar 上实证可行（compose→改节点→serialize + `processComments`，见 §8）。

## 2. 目标 / 非目标 / 验收

### 目标（可验证）

1. **G1 引擎单一来源**：`org.yaml.snakeyaml` 显式进 `gradle/libs.versions.toml`（单源 pin 2.6 = 服务端捆绑版本）；加固策略（SafeConstructor/禁 timestamp/重复键/上限）在引擎内一次定死。
2. **G2 lib 内收敛**：LanguageBundle 两处裸 `new Yaml().load()` 换门面，外部行为不变（现有测试全绿即护栏）。
3. **G3 工具链齐备**：lib 提供文件生命周期、类型化读取、issue 收集、configVersion 迁移链、**注释保留写回（§8）**，使消费方插件可整文件删除 Bukkit config import 与手写解析器。
4. **G4 PepperTreeCut 迁移**：`Yaml.java` 退役；`ConfigLoader`/`ConfigValidator` 领域语义（合并顺序、extends、tag 展开、soil 家族）不动；语料护栏测试与 387 基线保持绿。
5. **G5 家族逐步迁移**：§12 P3 清单内插件逐家切换，每家行为等价（默认值表对比测试 + 冒烟）。
6. **G6 测试矩阵一次建立全家共享**：语义矩阵测试、错误路径测试在 lib 内，全家族继承。
7. **G7 注释保留写回**：`YamlDoc`（Node 级编辑器）增补键/改值/删键后序列化，磁盘注释逐字保留；写回仅由显式场景触发（升级补键、管理命令），运行期不自动落盘；写回前原子写 + 可选备份。

### 非目标（明确不做）

- **数据文件不纳入**：GlowToggleStore 玩家开关、UserPrefix prefixes/*、Claim JSON 存储、PersistentStore——属运行时数据，继续走 persist 或各自 codec；配置文件（管理员手改的 settings）才在范围内。
- 不做注解/DSL 式 schema 框架、不做热重载框架、不做 GUI 编辑器。
- 不做 Bukkit YamlConfiguration 的 drop-in 兼容层（它是被替换对象，不是被兼容对象）。
- **运行时自动写回**（每次加载补默认落盘）不做——写回只在显式场景触发，且注释保留（§8）；写回不覆盖管理员已显式写下的键值（只补缺失）。
- 不做运行时状态写入配置文件的通道（数据文件分流）。
- 文本模板合并（ConfigUpdater 路线）不做首版实现，作为 Node 往返布局噪音不可接受时的 fallback 记档（§8.4）。

### 验收

- 全家族 grep：迁移后的插件源码零 `org.bukkit.configuration` import、零自研 YAML 解析器。
- lib `./gradlew check` 绿（junit + spotless + javadoc + 产物守卫 + japicmp）。
- lib 矩阵测试全绿（§6 语义表逐行覆盖）；LanguageBundle 现有测试不动全绿。
- **写回金样测试**：含注释/引号/flow list/空值的真实配置样本经 YamlDoc 增补键往返，磁盘文本差异 = 仅新增块（golden 文件断言）。
- PepperTreeCut：387 基线绿 + `ConfigResourceTest` 逐字语料护栏绿 + 测试服冒烟（含 profiles 多文件、土壤家族、FB 动画不受影响）。
- thin / shaded 双模式 Paper smoke 绿（沿用 `scripts/paper-smoke.sh`）。
- 每家迁移插件：默认值等价测试 + 实服冒烟记录。

## 3. 消费方现状矩阵

| 插件 | 配置形态 | 文件 | 读写模式 | 迁移难度 |
|---|---|---|---|---|
| PepperTreeCut | 自研子集解析 + 领域 ConfigLoader/Validator/Migrator | config.yml + profiles/*.yml（多文件、extends、字典序合并、同名覆盖） | 只读；校验 issue 报告；configVersion v1→v2 | 中（语义最重，测试护栏最全） |
| PepperLib i18n | 裸 snakeyaml | lang/<locale>.yml | 只读；宽容降级（坏文件 warn 不崩） | 低（G2 先行） |
| PepperClaim | ConfigSource SPI + BukkitYamlSource 实现 | config.yml + worlds.yml + tracks.yml + slots.yml… | 读写：缺键补默认并 `disk.save()` 落盘（**对象往返毁注释**）；diffLog() 报告 | 中（SPI 接缝现成；写回语义改为升级触发，§8.3） |
| PepperUnion | Bukkit FileConfiguration 手工 getter → 域 record | config.yml | 只读 | 中（PluginSettings 内部委托面大） |
| GlowingSquad | Bukkit config | config.yml（1 键） | 只读（数据文件除外） | 低 |
| PepperMinecart | Bukkit PluginConfig | config.yml | 只读 | 低 |
| PepperTrashBin | ConfigManager + StorageConfig | config.yml 等 | 混合 | 中 |
| UserPrefix / PepperPvpArena | Bukkit + 数据文件混合 | config.yml + 地图/前缀文件 | 混合 | 边界判定（OQ-5）后定 |

## 4. 方案对比（为何自建而非引第三方）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 自建门面下沉（本文） | 零新第三方依赖；语义自控且与家族代码风格一致（record + 手工 wiring）；一个加固点；矩阵一次建立；写回走 Node 级自控 | 引擎之上的工具链需自己维护 | **采纳**（用户已裁决方向） |
| 引 okaeri-configs（PepperClaim javadoc 曾预留 "未来可换 okaeri"） | 成熟：schema DSL、注释保留写回、迁移框架现成 | 新依赖面（其内部亦为 snakeyaml 封装，收益重叠）；DSL 风格与家族 record 风格冲突；版本跟随成本 | 否决；若未来写回需求升级为"任意值改写 + 注释保留 + 热编辑"，可重估（记录在案） |
| 维持现状（各写各的） | 零改动 | 四种技术并存、加固点分散、树cut 自研解析器继续由消费方维护 | 否决 |

## 5. 分层架构

```
┌ 消费方领域层（不进 lib）：ConfigLoader 合并/extends/tag 展开、PluginSettings 域 record、
│                     Claim Settings SPI 实现（换 ConfigSource 实现类即可）
├ L2 ltd.pepper.lib.config 工具链：ConfigIssue / IssueCollector / Values 类型化读取 /
│                     UnknownKeys / ConfigVersions（迁移链）/ UpgradePatch（默认模板 diff → 写回补键）
├ L1 ltd.pepper.lib.config 生命周期：默认资源首跑落盘（copy-once，注释安全）、
│                     readUtf8（BOM 剥离）、writeAtomic（temp+rename，对齐 persist 先例）
├ L0 ltd.pepper.lib.yaml 引擎：YamlMap.parse → Map<String,Object>（只读路径）
│                     YamlParseException(line,column,problem,cause)
│                     YamlDoc（Node 级文档对象：compose 含注释 → put/putIfAbsent/remove → serialize 注释保留）
└ 底座：org.yaml.snakeyaml 2.6（服务端捆绑，SafeConstructor + 定案策略封装于 L0）
```

分层纪律（延续 lib 现有做法）：

- **L0/L1/L2 零 Bukkit import、纯 JDK**：全部可 JVM 单测；消费方薄接线层才碰 Bukkit。
- 包名占位见 OQ-1；API 冻结纪律：进 lib 即被 japicmp 守护，P0/P1 定稿克制（最小面）。
- 依赖方向不变：treecut 等消费方 `compileOnly pepper-lib` + 运行期 PepperLib.jar 前置提供（thin 模式）；L0 运行期解析 `org.yaml.snakeyaml` 由服务端捆绑版本提供（2.6，Paper 已加固）——与 LanguageBundle 现状同构，README 依赖注明即可。

## 6. L0 引擎语义定案（矩阵测试逐行锁定）

| # | 语义 | 定案 | 理由 / 与现状差异 |
|---|---|---|---|
| S1 | 构造 | `SafeConstructor` 显式钉死 | 防 tag 任意类实例化；不随版本默认漂移 |
| S2 | 上限 | LoaderOptions 默认上限全保留（codePointLimit、alias 上限） | 防超长/别名炸弹 |
| S3 | 时间戳 | 自定义 Resolver **关闭** timestamp | 裸 ISO 日期按字符串（手写现状一致；Bukkit 会变 Date，属修复） |
| S4 | 布尔词表 | 跟随 snakeyaml 标准（true/false/yes/no/on/off，YAML 1.1） | 与 Bukkit YamlConfiguration 现状一致，无行为差；文档注明 |
| S5 | 数字 | 标准隐式解析（int/long/double、0x、下划线） | 比手写子集宽；手写把 `0x10` 当字符串——差异记入迁移自查表（语料无此形态） |
| S6 | 空文档/纯注释 | → 空 Map | 手写一致；**裸 snakeyaml 返回 null**——门面必须归一（防 NPE 的关键点） |
| S7 | 顶层非 map | YamlParseException（"顶层必须是映射"） | 手写抛"非法的键值行"，都算报错路径，文案统一 |
| S8 | 重复键 | 默认异常（DuplicateKeyException → YamlParseException，带键名与行号） | **收紧**：手写静默后者覆盖；与 Bukkit 现行为一致（同一引擎）；宽容消费方（LanguageBundle）自行 catch 降级 |
| S9 | 空值 `key:` | 标准语义 null | 手写给空 Map；消费方 mapOf/回落逻辑均以 null/缺键等价处理，无感知 |
| S10 | 键序 | LinkedHashMap 保序；Node 树保文档序 | 与手写/加载顺序一致；写回时键序 = 磁盘原序 + 新键追加/按默认模板位置 |
| S11 | 引号/转义/多行 `\|`/锚点/块序列 | 全支持（原语料禁区解除） | 手写会拒；文档注明"原本会被拒的文件现在能过" |
| S12 | 多文档 | **拒绝**（Yaml.load 单文档语义，遇第二文档直接报错） | 规范禁止多文档，防静默丢段；实测锁定（snakeyaml 2.6 `load` 遇 `---` 第二文档抛 "expected a single document"） |
| S13 | BOM | parse 前剥离 UTF-8 BOM | **修复**：手写会把 `\uFEFF` 粘进首键导致静默迁移误判 |
| S14 | 错误形态 | `YamlParseException(line, column, problem, cause)` | MarkedYAMLException 转结构化；中文文案由消费方组装（lib 保持语言中立） |
| S15 | 文件编码 | 调用方解码为 String 后 parse；L1 提供 readUtf8 | 引擎不管 IO |
| S16 | 注释开关 | 只读路径 parse 默认 processComments=false（快）；`YamlDoc` 路径默认 true（保留注释） | 两条路径共用同一解析器内核，仅开关差异，行为一致性有测试锁 |

L0 API 草案（最小面）：

```java
package ltd.pepper.lib.yaml;

public final class YamlMap {
    private YamlMap() {}
    /** 解析 YAML 文本为保序 Map；空文档/纯注释 → 空 Map；重复键/语法错 → YamlParseException。 */
    public static Map<String, Object> parse(String text);
}

public final class YamlParseException extends RuntimeException {
    public int line();       // 1-based；无位置信息时为 -1
    public int column();     // 1-based
    public String problem(); // snakeyaml 原问题描述（英文，含键名/上下文）
    // message() 由消费方按需本地化组装
}
```

## 7. L1 生命周期

```java
package ltd.pepper.lib.config;

public final class ConfigFile {
    /** 首跑落盘：资源 → 数据目录，目标存在则不动（原样复制 = 注释逐字保留）。返回是否新建。 */
    public static boolean copyDefaultIfMissing(Path dataFolder, String resourcePath, ClassLoader loader);
    /** 读文件为 UTF-8 字符串（BOM 剥离），文件缺失/IO 错抛 IOException。 */
    public static String readUtf8(Path file) throws IOException;
    /** 原子写（同目录 temp + rename，对齐 persist 写盘先例）；写回路径唯一出口。 */
    public static void writeAtomic(Path file, String content) throws IOException;
}
```

要点：

- 替代 treecut 的 `saveDefaultConfig()` / `saveResource(..., false)` 与 Claim 的资源复制逻辑——不依赖 Bukkit `JavaPlugin`（消费方传 classloader），可 JVM 测。
- **家族默认写策略 = 运行期永不自动写回**（§10 规范）；物理写盘只经 `writeAtomic` 且仅由显式场景（§8.3）触发。
- 运行时状态写入配置文件被规范禁止（数据文件分流）。

## 8. 写回：YamlDoc（Node 级注释保留编辑器）——OQ-2 裁决新增

### 8.1 路线与实证

裁决：支持写回，且不损失注释。路线 = **Node 级往返**（snakeyaml 2.x `compose` → 改节点 → `serialize`，Load/DumpOptions `processComments=true`），已在服务端捆绑 snakeyaml-2.6.jar 实测：

- 原样往返：顶部注释、块注释、行尾注释**全保留**；
- 追加带注释新键：注释随新键写出，但暴露 Node 层工程细节——CommentLine 文本自带 `#` 会被 emitter 再叠一个前缀、`Tag.STR` 会把裸数字强制引号（需按 `Tag.INT` 等构造）——这些由 lib 编辑器归一 + 测试锁定；
- 已知边界：emitter 会规范化布局（实测块序列缩进被压平），**非字节级保真**——磁盘差异 = 新增块 + 可能的重排噪音，见 8.4。

### 8.2 API 草案

```java
package ltd.pepper.lib.yaml;

/** Node 级文档：parse 时 processComments=true；编辑原语在 Node 树上操作，serialize 保留注释。 */
public final class YamlDoc {
    public static YamlDoc parse(String text);            // 语法/重复键错误同 YamlMap（S8/S12）
    /** 只读视图：转 Map（与 YamlMap.parse 同语义，null 文档 → 空 Map）。 */
    public Map<String, Object> asMap();
    /** 逐段路径访问/编辑（路径 = List<String> 段，不用点号字符串，杜绝 Bukkit 点号歧义）。 */
    public Object get(List<String> path);
    public boolean contains(List<String> path);
    /** 写回核心原语：键缺失才写入；value + 可选注释行（注释来自默认模板，S11 风格按 Node 自带样式保留）。 */
    public void putIfAbsent(List<String> path, Object value, List<String> commentLines);
    /** 显式改值（升级重定默认值时用；不自动触发）。 */
    public void put(List<String> path, Object value, List<String> commentLines);
    /** 显式删键（迁移清废弃键时用；默认模板带走的旧键由消费方裁决）。 */
    public void remove(List<String> path);
    /** 序列化（processComments=true）；键序 = 磁盘原序 + 新增键按默认模板相对位置插入。 */
    public String serialize();
    /** 与默认模板比较：返回磁盘缺、模板有的路径清单（含各自注释），供 UpgradePatch 用。 */
    public List<PathEntry> missingAgainst(Map<String, Object> defaults, List<String> pathPrefix);
}
```

### 8.3 触发策略（家族规范）

写回只发生在两类显式场景，**绝不**在常规加载路径自动落盘：

1. **升级补键（推荐主场景）**：`configVersion` 提升时，用新版默认资源对磁盘文件执行"只补缺失键 + 注释块"，随后 `writeAtomic`（可选先写 `config.yml.bak-v<旧版本>`）；管理员已显式写下的任何键值（含旧值）**一律不覆盖**（`putIfAbsent` 语义）；
2. **管理命令**：各插件 `/xxx config defaults` 之类显式命令触发同一补键流程（Claim diffLog 模式泛化）。

Claim 迁移说明（行为变更须通告）：现 `BukkitYamlSource` 每次加载缺键即补并 `disk.save()`（对象往返，注释每次全毁）→ 迁移后改为**升级/命令触发 + 注释保留写回**；每次加载只补内存默认 + diffLog 通告。若确需保持"每次加载落盘收敛"，技术上可用 `YamlDoc.putIfAbsent` 每载重写，但布局噪音与磁盘 churn 不推荐（§8.4），由用户裁决（OQ-2b）。

### 8.4 布局噪音与 fallback

- Node 往返非字节级保真：emitter 规范化缩进/空行布局。缓解：写回仅升级/命令触发（低频）；写回前备份；golden 金样测试断言"差异 ≈ 仅新增块"。
- **布局噪音验收 gate（spike 产出）**：以家族真实语料（treecut config.yml/profiles、Claim 多文件）跑 YamlDoc 增补键往返，统计全文件 diff 行数；若噪音超 gate（待定：新增块外 diff 行 ≈ 0 的目标，放宽阈值 ≤ 结构无关行数的上限），切换 fallback。
- Fallback 记档：**文本模板合并**（磁盘原文逐字节保留、只插入缺失键块）——"只增不改"场景保真最高；代价是字符串手术与只增限制；首版不做，spike 结论驱动（OQ-2c）。

## 9. L2 工具链

```java
package ltd.pepper.lib.config;

public enum IssueLevel { ERROR, WARN }
public record ConfigIssue(IssueLevel level, String path, String message, String hint) {}
    // path 采用点号文案（如 "profiles.10-vanilla.wood"）；仅是诊断定位，不参与取值

public final class IssueCollector {
    public void add(ConfigIssue issue);
    public List<ConfigIssue> issues();          // 不可变视图
    public boolean hasErrors();
    public int count(IssueLevel level);
}

public final class Values {
    // 与 treecut 现有私有助手同名同签名（迁移机械、行为等价：类型不匹配 → 静默回落默认值）
    public static boolean boolOf(Map<String, Object> m, String key, boolean def);
    public static int intOf(Map<String, Object> m, String key, int def);
    public static long longOf(Map<String, Object> m, String key, long def);
    public static double doubleOf(Map<String, Object> m, String key, double def);
    public static String stringOf(Map<String, Object> m, String key, String def);
    public static List<String> stringListOf(Object value);          // Iterable 归一；标量 → 单元素
    public static <E extends Enum<E>> E enumOf(Class<E> type, Map<String, Object> m,
            String key, E def, IssueCollector issues);              // 未知值 → WARN + 回落默认
}

public final class UnknownKeys {
    /** 单层未知键检测（返回不在 known 中的键）；递归/任意键段由消费方组织（treecut ConfigValidator 现状）。 */
    public static List<String> unknown(Map<String, Object> map, Set<String> known);
}

public final class ConfigVersions {
    public static int versionOf(Map<String, Object> root);          // 缺省 = 1；非整数 → 1 + 上报？
    /** 链式迁移：从 versionOf 起逐级应用到 currentVersion；返回是否发生迁移。 */
    public static boolean migrate(Map<String, Object> root, int currentVersion,
            Map<Integer, UnaryOperator<Map<String, Object>>> stepByFrom, IssueCollector issues);
}

/** 升级补键：默认模板 vs 磁盘文档 → 只补缺失（含注释块）。内部 = YamlDoc.missingAgainst + putIfAbsent。 */
public final class UpgradePatch {
    public static PatchPlan plan(YamlDoc disk, Map<String, Object> defaults,
            List<String> pathPrefix, List<String> resourceCommentHints);
    public static String applyAndSerialize(YamlDoc disk, PatchPlan plan);   // 原子写由消费方走 ConfigFile.writeAtomic
}
```

关键设计决策（与 Bukkit 语义的坑隔离）：

- **取值 = 单层键访问**（`map.get(key)`），点号只出现在 issue 文案里；**YamlDoc 编辑路径 = 段列表**（`List<String>`）——两处都绕开 Bukkit 的"路径 vs 字面键"歧义；treecut 现有调用形态（`intOf(gravity, "maxFallDistancePerBreak", ...)`）逐行机械迁移。
- L2 不提供：默认值树/补默认合并（内存默认归消费方）、BlockKeySet/tag 展开、MaterialLookup——领域语义留消费方（treecut `setOf` 驻留）。
- `migrate` 语义对齐 treecut ConfigMigrator 模式（v1→v2 删键/改名/复制），步骤函数按 from 版本注册，在**内存副本**上执行；物理落盘只经 §8.3 触发。
- `UpgradePatch.plan` 与写回解耦：plan 可在加载期纯内存计算（diffLog 通告），apply 才碰盘。

## 10. 消费方接线模式（规范，非框架）

统一启动序列（各插件 onEnable）：

1. `ConfigFile.copyDefaultIfMissing(dataFolder, "config.yml", loader)` —— 新装服务器得到带注释的默认文件；
2. `readUtf8` → `YamlMap.parse`（L0）→ `ConfigVersions.migrate`（root 副本上执行，原文件不动）；
3. **升级补键（可选开启）**：`versionOf < schemaVersion` 时，用默认资源构造 `UpgradePatch.plan` → `YamlDoc` 重写 → 备份（.bak-v<旧>）→ `ConfigFile.writeAtomic`；失败不阻塞启动（告警 + 内存默认兜底）；
4. 消费方领域解析函数 `parse(Map, ...) → (T, issues)`：用 `Values`/`UnknownKeys`，把值装进不可变 record/builder（treecut RuntimeConfig / Union 域 record / Claim Settings 各实现），语义归一（枚举回落、tag 展开、土壤家族）放各自领域层；
5. issues 按 ERROR/WARN 处理（treecut 门禁语义保留：全 profile 禁用才 fatal——领域规则，不进 lib）。

家族文件规范（写进各插件 README / 配置头注释）：

- 根键 `configVersion: N`（整数，缺省 1）；单文档文件；UTF-8；2 空格缩进；禁 tab；`#` 注释；
- **运行期永不自动写回**；升级补键 = 只补缺失不覆盖 + 注释保留（§8.3），管理员已改值一律尊重；
- 文件键含点号必须引号；lang/*.yml 为扁平字符串 map，复用 L0+L1 不走 schema。

## 11. 分期落地（每期一个 lib minor，独立评审点）

### P0（lib 0.10.0）—— 引擎 + lib 内收敛

1. `gradle/libs.versions.toml`：显式 `org.yaml:snakeyaml:2.6`（compileOnly + testImplementation）；
2. `ltd.pepper.lib.yaml`：`YamlMap` + `YamlParseException`，§6 语义表 S1–S16 逐行矩阵测试（TDD 红绿）；
3. LanguageBundle 两处裸 `new Yaml().load()` 换门面——现有测试即行为护栏（宽容 catch 保留在调用点）；
4. README 稳定性表新行（Experimental）+ 依赖注明（运行期 = 服务端捆绑 snakeyaml ≥2.6）；CHANGELOG；
5. `./gradlew check` 全绿（javadoc/spotless/japicmp）→ publish。

### P1（lib 0.11.0）—— 工具链 + 写回

1. **写回 spike 先行**：家族真实语料 YamlDoc 往返 → 布局噪音 diff 统计 → §8.4 gate 判定（Node 往返定案 or 切文本合并 fallback）；
2. `ltd.pepper.lib.yaml.YamlDoc`：Node 编辑器 + 注释保留金样测试（§2 验收）；
3. `ltd.pepper.lib.config`：ConfigFile / IssueCollector / Values / UnknownKeys / ConfigVersions / UpgradePatch + 各自测试；
4. 无消费方行为变化（纯新增）。

### P2 —— PepperTreeCut 迁移（先重后轻，树cut 语义最重先验明模式）

1. 分支：`Yaml.java` 退役；`ConfigLoader`/`ConfigValidator` 私有助手换 `Values`；枚举回落经 `enumOf(...issues)`（WARN 行为对齐 ConfigValidator 现状）；
2. 语料护栏（ConfigResourceTest 逐字解析）+ 387 基线全绿；测试服冒烟（profiles 多文件/土壤家族/FB 动画回归）；
3. 合并 + 打标。

### P3 —— 逐家迁移（每插件独立分支，先简单后复杂）

GlowingSquad → PepperMinecart（单键，各半日）→ PepperTrashBin → PepperUnion（PluginSettings 内部实现换，域 record 不动）→ **PepperClaim**（兑现 ConfigSource SPI 预留：新实现类换入；`disk.save()` 语义改升级/命令触发写回，行为变更通告 OQ-2b）→ UserPrefix / PepperPvpArena（边界判定 OQ-5）。

每家验收：默认值等价测试（新旧默认值表对比）+ 实服冒烟 + 零 `org.bukkit.configuration` import。

## 12. 测试与发布

- lib 内测试（全部纯 JDK，无 mockbukkit 需求）：S1–S16 矩阵、空/纯注释/顶层非 map/重复键/BOM/多文档/错误行列、L1（copy 不覆盖/原子写）、L2 各 reader 类型矩阵与回落、迁移链、UpgradePatch（缺键补入/已有键不覆盖/注释随块）、**YamlDoc 金样**（真实语料往返 diff = 仅新增块）、LanguageBundle 收敛护栏。
- treecut 侧：ConfigResourceTest 原文语料 + 既有领域测试（387）不改语义只改调用。
- 发布门：lib `check` 绿 → thin/shaded 双 smoke → publish → treecut 等 bump `pepperLibVersion`（gradle.properties 单源 + verifyPepperLibVersion 任务）→ 测试服替换 PepperLib.jar + 插件 jar 冒烟。
- japicmp 纪律：P0/P1 API 面冻结后，破坏性变更需迁移指南（README 稳定性策略 0.2.x）。

## 13. 风险登记

| 风险 | 缓解 |
|---|---|
| API 过早冻结（japicmp 守护后改签名成本高） | P0/P1 面最小化：L0 单入口单异常；L2 先只搬"与 treecut 现有助手同形"的静默读取，诊断增强留后续 minor |
| 发布节奏耦合（每期 lib minor + 各消费方 bump + 测试服换 jar） | 分期即评审点；P2/P3 每家独立分支，可随时停 |
| LanguageBundle 行为回归 | 现有测试不动即护栏；宽容降级留在调用点 |
| 服务端 snakeyaml 版本漂移（现捆绑 2.6） | 编译 pin 2.6 = 服务端版本；升级只改 toml 一处；门面 + 矩阵测试吸收行为差异 |
| **Node 写回布局噪音**（emitter 规范化缩进/空行；实测序列缩进压平） | 写回低频（升级/命令）；写前备份；金样测试 gate（§8.4）；不可接受则切文本合并 fallback（记档） |
| **Node 写回工程细节**（CommentLine `#` 前缀叠加、Tag 强引号、锚点/别名重建） | lib 编辑器归一 + 专项测试锁定（§8.1 实证发现的坑逐条入测） |
| **写回覆盖管理员键值**（最危险的语义错误） | 写回 = `putIfAbsent` 唯一语义；"已有键永不改写"入金样与契约测试；升级只动 configVersion 门内 |
| treecut 错误文案/ValidationReport 类型变动引入回归 | OQ-4 裁决：推荐统一到 lib ConfigIssue（树cut 文案不动，仅类型换源） |
| 手写解析器"宽容"变引擎"严格"（重复键等） | 收紧点全部在矩阵测试锁定 + 迁移自查表通告（§6 S8/S9/S11/S12） |

## 14. 开放问题（待裁决）

已裁决：**OQ-2 = 支持写回**（Node 级注释保留，升级/命令触发，不覆盖已有键，§8 并入本版）；**OQ-6 = 写回入范围**（P1 实现；文本合并路线降级为 fallback）。

- **OQ-1 包名**：`ltd.pepper.lib.yaml`（引擎 + YamlDoc）+ `ltd.pepper.lib.config`（工具链）是否可接受？（备选：合并单包 `ltd.pepper.lib.config` 全部收纳）
- **OQ-2b Claim 语义**：迁移后确认放弃"每次加载补默认落盘"、改为升级/命令触发写回？（推荐：是——否则每次加载全文件重排；若坚持每载落盘则接受布局噪音与 churn）
- **OQ-2c 布局噪音 gate**：§8.4 验收标准（金样 diff 仅新增块，放宽阈值上限待 spike 统计后定）由 spike 结果拍板，是否认可此流程？
- **OQ-3 迁移顺序**：P2 treecut 先行（语义最重先验明模式，推荐）还是先拿 GlowingSquad 类简单插件练手？
- **OQ-4 treecut 报告类型**：ValidationReport 是否直换 lib ConfigIssue/IssueCollector（树cut 现有文案不变，仅类型换源）？还是 lib 只做容器、treecut 留薄包装过渡？
- **OQ-5 边界**：UserPrefix prefixes/* 与 PepperPvpArena 地图文件确认为"数据文件"排除出迁移范围？（推荐确认，与 §2 非目标一致）

## 15. 已否决/已定案的记录

- 引 okaeri-configs：否决（§4），重估条件记录在案（写回需求升级为任意值改写 + 热编辑时）。
- 布尔 yes/no/on/off：跟随 snakeyaml 标准（与 Bukkit 现状一致），不做词表收紧（§6 S4）。
- 取值一律单层键、点号仅诊断；YamlDoc 编辑路径 = 段列表：定案（§9），绕开 Bukkit 路径歧义。
- 数据文件（persist 体系）不纳入：定案（§2）。
- LanguageBundle 宽容语义（坏文件 warn 不崩）保留在调用点，不因引擎加严而改变：定案（§6 S8）。
- 写回路线：**Node 级往返为定案路线**（§8，捆绑 2.6 实证可行）；文本模板合并 = fallback，触发条件 = 布局噪音 gate 不过（§8.4）——两者均在 P1 spike 用真实语料验证。
- 写回触发：仅升级补键（configVersion 门内、putIfAbsent、写前备份）与显式管理命令；运行期永不自动写回：定案（§8.3）。
