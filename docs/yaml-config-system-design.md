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

额外事实（支撑下沉）：PepperLib 0.9.0 的 LanguageBundle 已构成运行期依赖服务端捆绑 snakeyaml 的先例——下沉**不引入新依赖面**，而是把隐式依赖显式化、策略单一来源化。写回路线经 §8.4 spike 实证裁定：**Node 往返（compose→改→serialize）布局噪音不可接受，定案为文本模板合并**（磁盘字节原样 + 只插入缺失键块）。

## 2. 目标 / 非目标 / 验收

### 目标（可验证）

1. **G1 引擎单一来源**：`org.yaml.snakeyaml` 显式进 `gradle/libs.versions.toml`（单源 pin 2.6 = 服务端捆绑版本）；加固策略（SafeConstructor/禁 timestamp/重复键/上限）在引擎内一次定死。
2. **G2 lib 内收敛**：LanguageBundle 两处裸 `new Yaml().load()` 换门面，外部行为不变（现有测试全绿即护栏）。
3. **G3 工具链齐备**：lib 提供文件生命周期、类型化读取、issue 收集、configVersion 迁移链、**注释保留写回（§8）**，使消费方插件可整文件删除 Bukkit config import 与手写解析器。
4. **G4 PepperTreeCut 迁移**：`Yaml.java` 退役；`ConfigLoader`/`ConfigValidator` 领域语义（合并顺序、extends、tag 展开、soil 家族）不动；语料护栏测试与 387 基线保持绿。
5. **G5 家族逐步迁移**：§12 P3 清单内插件逐家切换，每家行为等价（默认值表对比测试 + 冒烟）。
6. **G6 测试矩阵一次建立全家共享**：语义矩阵测试、错误路径测试在 lib 内，全家族继承。
7. **G7 注释保留写回**：`YamlMerge`（文本模板合并）只补缺失键块（含其注释），磁盘其它字节**逐字不动**；写回仅由显式场景触发（升级补键、管理命令），运行期不自动落盘；写回前原子写 + 可选备份。

### 非目标（明确不做）

- **数据文件不纳入**：GlowToggleStore 玩家开关、UserPrefix prefixes/*、Claim JSON 存储、PersistentStore——属运行时数据，继续走 persist 或各自 codec；配置文件（管理员手改的 settings）才在范围内。
- 不做注解/DSL 式 schema 框架、不做热重载框架、不做 GUI 编辑器。
- 不做 Bukkit YamlConfiguration 的 drop-in 兼容层（它是被替换对象，不是被兼容对象）。
- **运行时自动写回**（每次加载补默认落盘）不做——写回只在显式场景触发，且注释保留（§8）；写回不覆盖管理员已显式写下的键值（只补缺失）。
- 不做运行时状态写入配置文件的通道（数据文件分流）。
- Node 级重排写回（serialize 全量重发）不做——§8.4 spike 已裁定布局噪音不可接受。

### 验收

- 全家族 grep：迁移后的插件源码零 `org.bukkit.configuration` import、零自研 YAML 解析器。
- lib `./gradlew check` 绿（junit + spotless + javadoc + 产物守卫 + japicmp）。
- lib 矩阵测试全绿（§6 语义表逐行覆盖）；LanguageBundle 现有测试不动全绿。
- **写回金样测试**：含注释/引号/flow list/空值的真实配置样本经 YamlMerge 合并，磁盘文本差异 = 仅新增块（golden 断言，逐字比对）。
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
| GlowingSquad | 自研注解绑定 `@ConfigModel` + ConfigFileStore（0.12.0 起；原 ConfigLib 试点已反迁） | config.yml（1 键） | 只读（数据文件除外） | 低 |
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
│                     YamlMerge（文本模板合并：compose 拿 AST 行号锚点 → 缺失键块原样插入，磁盘其它字节不动）
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
| S16 | 注释开关 | 只读路径 parse 默认 processComments=false（快）；`YamlMerge` 内部 compose 用 true（取注释行号） | 两条路径共用同一解析器内核，仅开关差异，行为一致性有测试锁 |

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

## 8. 写回：YamlMerge（文本模板合并，磁盘字节原样）——OQ-2 裁决新增

### 8.1 路线裁定：spike 实证否决 Node 往返

裁决：支持写回，且不损失注释。曾有两候选路线，**P1 先跑 spike 再定**（OQ-2c）：

1. **Node 级往返**（compose → 改节点 → serialize，`processComments=true`）——技术上可行（服务端捆绑 2.6 实测注释全保留），但 **spike 实测布局噪音巨大，裁定否决**：
   - 真实语料（treecut config.yml 100 行 / profiles 各 20 行）追加一个带注释新键后：config.yml 行集差 **+46/−44**，10-vanilla **+15/−12**，20-custom **+13/−9**——60%~75% 行被重排；
   - 根因：行尾注释对齐空格被单空格化、裸 flow item 被强制引号、多行 flow list 重新折行、冒号后对齐全丢；
   - 结论：对管理员手改、以 git diff 为工作流的配置文件不可接受，**离"仅新增块"目标差一个数量级**。
2. **文本模板合并（定案）**：磁盘文本**逐字节不动**，只把默认模板中缺失键的整块（含其前置注释）**原样插入**磁盘；语义判断用 snakeyaml compose 的 AST（键存在性、行号锚点），块来源与插入位都用**源文本行切片**，不做全量重发——diff = 仅新增块。

### 8.2 API 草案

```java
package ltd.pepper.lib.yaml;

/** 文本模板合并：磁盘 + 默认模板 → 合并文本（只增缺失键，磁盘其它字节原样）。 */
public final class YamlMerge {
    public record Result(String merged, boolean changed, List<String> insertedPaths) {}

    /** 磁盘文本与默认模板合并。磁盘缺、模板有的键块（含前置注释，按模板原缩进）插入；
     *  磁盘已存在的路径（无论值类型）一律不动——putIfAbsent 语义。
     *  磁盘/模板语法错抛 YamlParseException（同 YamlMap）。 */
    public static Result merge(String diskText, String templateText);
}
```

实现要点：

- **缺失路径**：`compose` 两个文件（processComments=true 取 AST 行号）→ 递归比对 Map 键集合，收集缺失路径 + 对应模板节点；
- **块来源**：模板节点原文行切片 `[前置注释首行 .. 值末行]`（AST 的 start/end mark + 节点 blockComments 定位，天然含注释与多行 flow list）；
- **插入锚点**：顶层缺失键 → 磁盘末尾（保留既有末尾换行约定）；嵌套缺失键 → 磁盘该 section 内最后一个子键之后，模板块按 section 层级重缩进（重缩进 = 相对模板基准缩进平移）；
- **不改动**：磁盘上任何既有字节（含行尾对齐空格、引号风格、空行）——由金样测试逐字断言。
- 典型调用方 = `UpgradePatch`（§9）：`plan` 阶段纯内存算缺失清单（diffLog 通告），`apply` 阶段 `YamlMerge.merge` → `ConfigFile.writeAtomic`（先备份）。

### 8.3 触发策略（家族规范）

写回只发生在两类显式场景，**绝不**在常规加载路径自动落盘：

1. **升级补键（推荐主场景）**：`configVersion` 提升时，用新版默认资源对磁盘文件执行"只补缺失键 + 注释块"，随后 `writeAtomic`（可选先写 `config.yml.bak-v<旧版本>`）；管理员已显式写下的任何键值（含旧值）**一律不覆盖**（`putIfAbsent` 语义）；
2. **管理命令**：各插件 `/xxx config defaults` 之类显式命令触发同一补键流程（Claim diffLog 模式泛化）。

Claim 迁移说明（行为变更须通告）：现 `BukkitYamlSource` 每次加载缺键即补并 `disk.save()`（对象往返，注释每次全毁）→ 迁移后改为**升级/命令触发 + 注释保留写回**；每次加载只补内存默认 + diffLog 通告。

### 8.4 边界与验收 gate

- 文本合并**只增不改**：删/改既有键不在写回范围（迁移删键只走内存 `ConfigVersions.migrate`，磁盘留旧键 + 未知键 WARN，管理员自行清理）；若未来出现"任意值改写 + 注释保留"硬需求，重估 okaeri（§4 已记档）。
- **验收 gate（spike 已过）**：真实语料合并后 diff = 仅新增块（金样逐字断言）；spike 数据已存档（§8.1）；Node 往返数据一并存档为"为何不用"的证据。

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
    public static int versionOf(Map<String, Object> root);          // 缺省 = 1；非整数 → 1
    /** 链式迁移：从 versionOf 起逐级应用到 currentVersion（步骤缺省停原地、防死循环）；返回是否迁移。 */
    public static boolean migrate(Map<String, Object> root, int currentVersion,
            Map<Integer, UnaryOperator<Map<String, Object>>> stepByFrom);
}

/** 升级补键：默认模板 vs 磁盘 → 只补缺失（含注释块）。内部 = YamlMerge.merge。 */
public final class UpgradePatch {
    public static PatchPlan plan(String diskText, String templateText);   // 纯内存算缺失清单（diffLog 通告）
    public static YamlMerge.Result apply(String diskText, String templateText); // = YamlMerge.merge；原子写由消费方走 ConfigFile.writeAtomic
}
```

关键设计决策（与 Bukkit 语义的坑隔离）：

- **取值 = 单层键访问**（`map.get(key)`），点号只出现在 issue 文案里；YamlMerge 路径 = 缺失路径段列表——两处都绕开 Bukkit 的"路径 vs 字面键"歧义；treecut 现有调用形态（`intOf(gravity, "maxFallDistancePerBreak", ...)`）逐行机械迁移。
- L2 不提供：默认值树/补默认合并（内存默认归消费方）、BlockKeySet/tag 展开、MaterialLookup——领域语义留消费方（treecut `setOf` 驻留）。
- `migrate` 语义对齐 treecut ConfigMigrator 模式（v1→v2 删键/改名/复制），步骤函数按 from 版本注册，在**内存副本**上执行；物理落盘只经 §8.3 触发。
- `UpgradePatch.plan` 与写回解耦：plan 可在加载期纯内存计算（diffLog 通告），apply 才碰盘。

## 10. 消费方接线模式（规范，非框架）

统一启动序列（各插件 onEnable）：

1. `ConfigFile.copyDefaultIfMissing(dataFolder, "config.yml", loader)` —— 新装服务器得到带注释的默认文件；
2. `readUtf8` → `YamlMap.parse`（L0）→ `ConfigVersions.migrate`（root 副本上执行，原文件不动）；
3. **升级补键（可选开启）**：`versionOf < schemaVersion` 时，用默认资源算 `UpgradePatch.plan` → `YamlMerge.merge` → 备份（.bak-v<旧>）→ `ConfigFile.writeAtomic`；失败不阻塞启动（告警 + 内存默认兜底）；
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

1. **写回 spike 先行（已完成）**：家族真实语料布局噪音 diff 统计 → §8.4 gate 判定——**Node 往返否决，文本模板合并定案**（§8.1 存档数据）；
2. `ltd.pepper.lib.yaml.YamlMerge`：文本模板合并 + 金样测试（§2 验收）；
3. `ltd.pepper.lib.config`：ConfigFile / IssueCollector / Values / UnknownKeys / ConfigVersions / UpgradePatch + 各自测试；
4. 无消费方行为变化（纯新增）。

### P2 —— PepperTreeCut 迁移（先重后轻，树cut 语义最重先验明模式）

1. 分支：`Yaml.java` 退役；`ConfigLoader`/`ConfigValidator` 私有助手换 `Values`；枚举回落经 `enumOf(...issues)`（WARN 行为对齐 ConfigValidator 现状）；
2. 语料护栏（ConfigResourceTest 逐字解析）+ 387 基线全绿；测试服冒烟（profiles 多文件/土壤家族/FB 动画回归）；
3. 合并 + 打标。

### P3 —— 逐家迁移（每插件独立分支，先简单后复杂）

GlowingSquad → ~~PepperTreeCut~~（2026-09-10 完成，见 §16.2）→ ~~PepperMinecart~~（完成）
→ PepperTrashBin → ~~PepperUnion~~（PluginSettings 内部实现换，域 record 不动，完成）→
**PepperClaim**（兑现 ConfigSource SPI 预留：新实现类换入；`disk.save()` 语义改升级/命令触发写回，行为变更通告 OQ-2b）→ UserPrefix / PepperPvpArena（边界判定 OQ-5）。

每家验收：默认值等价测试（新旧默认值表对比）+ 实服冒烟 + 零 `org.bukkit.configuration` import。
（P3 首批三家已完成默认值等价测试与零 import 复核；实服冒烟按当前裁决延后。）

## 12. 测试与发布

- lib 内测试（全部纯 JDK，无 mockbukkit 需求）：S1–S16 矩阵、空/纯注释/顶层非 map/重复键/BOM/多文档/错误行列、L1（copy 不覆盖/原子写）、L2 各 reader 类型矩阵与回落、迁移链、UpgradePatch（缺键补入/已有键不覆盖/注释随块）、**YamlMerge 金样**（真实语料合并 diff = 仅新增块，逐字断言）、LanguageBundle 收敛护栏。
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
| **文本合并锚点/重缩进边界**（嵌套键插入位、多行 flow list 块切片、注释行归属） | 金样逐字断言；锚点取自 AST 行号（compose marks）而非启发式文本搜索；spike 语料回归 |
| **写回覆盖管理员键值**（最危险的语义错误） | 写回 = `putIfAbsent` 唯一语义；"已有键永不改写"入金样与契约测试；升级只动 configVersion 门内 |
| treecut 错误文案/ValidationReport 类型变动引入回归 | OQ-4 裁决：推荐统一到 lib ConfigIssue（树cut 文案不动，仅类型换源） |
| 手写解析器"宽容"变引擎"严格"（重复键等） | 收紧点全部在矩阵测试锁定 + 迁移自查表通告（§6 S8/S9/S11/S12） |

## 14. 开放问题（待裁决）

已裁决：**OQ-2 = 支持写回**（Node 级注释保留，升级/命令触发，不覆盖已有键，§8 并入本版）；**OQ-6 = 写回入范围**（P1 实现；文本合并路线降级为 fallback）。

- **OQ-1 包名**：`ltd.pepper.lib.yaml`（引擎 + YamlMerge）+ `ltd.pepper.lib.config`（工具链）是否可接受？（备选：合并单包 `ltd.pepper.lib.config` 全部收纳）
- **OQ-2b Claim 语义**：迁移后确认放弃"每次加载补默认落盘"、改为升级/命令触发写回？（推荐：是——否则每次加载全文件重排；若坚持每载落盘则接受布局噪音与 churn）
- **OQ-2c 布局噪音 gate**：§8.4 验收标准（金样 diff 仅新增块，放宽阈值上限待 spike 统计后定）由 spike 结果拍板，是否认可此流程？
- **OQ-3 迁移顺序**：P2 treecut 先行（语义最重先验明模式，推荐）还是先拿 GlowingSquad 类简单插件练手？
- **OQ-4 treecut 报告类型**：ValidationReport 是否直换 lib ConfigIssue/IssueCollector（树cut 现有文案不变，仅类型换源）？还是 lib 只做容器、treecut 留薄包装过渡？
- **OQ-5 边界**：UserPrefix prefixes/* 与 PepperPvpArena 地图文件确认为"数据文件"排除出迁移范围？（推荐确认，与 §2 非目标一致）

## 15. 已否决/已定案的记录

- 引 okaeri-configs：否决（§4），重估条件记录在案（写回需求升级为任意值改写 + 热编辑时）。
- **绑定/schema 层**：原「自研退役、采用 Exlll ConfigLib（§16，2026-09-09 裁决）」于同日出反转
  ——**重新自研**（§16.1，0.12.0 实现：注解绑定 + 注释操作 + 运行时存储），ConfigLib 计划废弃。
- 布尔 yes/no/on/off：跟随 snakeyaml 标准（与 Bukkit 现状一致），不做词表收紧（§6 S4）。
- 取值一律单层键、点号仅诊断；YamlMerge 按缺失路径段列表工作：定案（§9），绕开 Bukkit 路径歧义。
- 数据文件（persist 体系）不纳入：定案（§2）。
- LanguageBundle 宽容语义（坏文件 warn 不崩）保留在调用点，不因引擎加严而改变：定案（§6 S8）。
- 写回路线：**spike 实证裁定文本模板合并为定案路线**（§8.1，真实语料数据：Node 往返 config.yml +46/−44、10-vanilla +15/−12、20-custom +13/−9）——磁盘字节原样 + 只插缺失键块；Node 往返否决并记档为"为何不用"证据。
- 写回触发：仅升级补键（configVersion 门内、putIfAbsent、写前备份）与显式管理命令；运行期永不自动写回：定案（§8.3）。

## 16. 绑定层裁决反转：自研替代 ConfigLib（0.12.0）

> **2026-09-09 用户裁决反转**：绑定/schema 层**不自研 → 自研**。下方原 §16 记录保留作历史
> 审计（曾裁决采用 Exlll ConfigLib）；0.12.0 起以自研实现替换（§16.1），GlowingSquad 已反向迁移。

### 16.1 自研方案（现行，0.12.0）

- **绑定**：注解驱动 `@ConfigModel`/`@ConfigPath`/`@ConfigComment`/`@ConfigRange` →
  `Bindings`（scheme 内核 `ConfigSchema` 为 package-private 内部实现，不进入 japicmp 面）。
  支持 POJO（字段初始化默认值 + 嵌套 `@ConfigModel` 节）与 record（组件注解，默认值类型零值）。
- **注释操作**（核心需求）：
  - `YamlComments` 解析条目 → 归属注释（块注释 = 条目标正上方 `#` 行，行内 = 同行尾部 `#`；
    空白分隔行过滤）；
  - `ConfigDoc.withComments` 运行时改块/行内注释，`withValue` 改条目标值——**字节保真**
    （只动目标区，复用 §8 YamlMerge 的 AST 行号切片路线）；
  - `@ConfigComment` 注解在默认文件发射/材质化时写入注释。
- **运行时存储**：`ConfigFileStore<T>`（首跑材质化 / set/setComments 即时重绑定 / save 原子写 /
  reload 失败保旧 / upgrade 升级补键）+ `ConfigGroup` 多文件聚合。
- **Configurate 能力对齐增量（0.12.0 追加）**：`ConfigDoc.children` 子节点遍历 +
  `mergeDefaults` 节内 putIfAbsent 合并；文档头注释 `@ConfigHeader`/`header()`/`withHeader()`；
  自定义类型序列化注册点 `ConfigCodec<T>` + `@Codec`（字段级；接口与注解避免同名）。
  保持自研 API、零新依赖，不做 HOCON/JSON 多格式、不引入 org.spongepowered.configurate。
- **附加约束**：继续零新第三方依赖（仅 snakeyaml 2.6）；写回仍只显式触发、字节保真、
  putIfAbsent 不覆盖管理员值。
- **迁移**：GlowingSquad 已从 ConfigLib 反向迁移（`@ConfigPath("max-distance")` + ConfigFileStore，
  `SquadConfigStoreTest` 4 例描述新行为：kebab 映射/未知键不动盘/缺键回落/**空文件回默认**
  ——与 ConfigLib 空文件抛异常不同，为改进）；其余 P3 插件迁移风格 = 本方案 + §7/§8/§9 工具链。

### 16.2 P3 首批三家迁移（0.13.0）——TreeCut → Minecart → Union

> **2026-09-10 完成**：PepperTreeCut、PepperMinecart、PepperUnion 三家配置层迁到
> `@ConfigModel` + `ConfigFileStore`；lib 0.13.0 为此新增下列能力。验收全绿：
> 构建（含 spotless/spotbugs/verifyPepperLibVersion 门）+ 默认值等价测试 + 迁移层零
> `org.bukkit.configuration` import；实服冒烟按裁决延后。

**lib 0.13.0 新增（全部 additive，无破坏性变更）**：

- **Map 一等绑定**（`ConfigSchema.ValueType.MAP`）：`Entry` 增加 `min/max/clamp`
  （作为数值列附加在 MAP 行）；`Builder.field` 全参数重载；`Bindings` 的
  `typeOf/typeZero/coerce/coerceKey/coerceValue/mapTypeArgs/emit` 全链支持嵌套 Map
  逐行缩进（空 `{}` 发射）。Union 的限高/经验曲线、tier caps 借此直绑（或按需以
  原始 `Map<String,Object>` 绑定，逐条校验语义保留在域层）。
- **`ConfigPostLoad<T>` 后处理钩子**（函数式接口 `apply(T, IssueCollector)`）：
  `Bindings.load(..., postLoad)` 与 `ConfigFileStore.load(..., postLoad)` 重载，
  store 的 read/commitDoc/reload 三径贯穿。TreeCut 方块集解析、Minecart 材质集
  解析、Union 领域归一后的跨字段派生（如 name-pattern 联动 max-name-length）落此处。
- **`@ConfigRange(clamp=true)` 夹紧模式**：越界修正到边界保留数值（而非回落默认），
  复刻 Minecart 旧 `intInRange/doubleInRange` 的夹紧语义。
- **NaN/Infinity 拦截**（S15 延续）：INT/LONG/DOUBLE resolve 先 `Double.isFinite`，
  非有限 → WARN + 回落默认。
- **枚举大小写不敏感**：`valueOf(...trim().toUpperCase(Locale.ROOT))`，对齐 Minecart
  旧 `parseEnum` 大写化语义。
- **emit 兄弟节 bug 修复**：旧 stack 逻辑无法处理同深度异键兄弟节（重复键）→ 重写为
  longest-common-prefix chain（`common` 前缀 + `chain.remove` + 新节展开），
  新增 MultiSection/emitSeparatesSiblingSectionsAtSameDepth 测试锁定。
- **`YamlScalar` flow map 发射**：`Map` 编码为 `{k: v}`、可作 flow list 元素——支持
  Union `activity.tiers` 默认形态的模型出货。

**三家迁移形态（行为等价，域 record / getter 面不动）**：

| 插件 | 模型 | 迁移要点 |
|---|---|---|
| PepperTreeCut | `TreeCutConfigModel` | config.yml 主树注解绑定（camelCase 显式 `@ConfigPath`）；profiles 多文件/tag 展开留 ConfigLoader 领域层；方块集经 post-load + `scalarTolerant`（标量→单元素宽容）+ raw root 补读；minFall 缺省 = max 的跨字段默认经 post-load + `containsDotted` |
| PepperMinecart | `MinecartConfigModel` | `PluginConfig` 改为 facade（ConfigFileStore + volatile 模型 + 旧 getter 全保留）；数值 `clamp=true`；枚举大小写不敏感；Material 集经 post-load matchMaterial+isBlock（WARN 跳过）；pepperlib-api.properties 版本门；测试从 `apply(FileConfiguration)` 改 `getValues(true)` |
| PepperUnion | `UnionSettingsModel` | `PluginSettings.from(FileConfiguration)` → `from(UnionSettingsModel)`（域 record 与旧 getter 不动，全部 LOG 告警与逐条校验语义保留在 from）；Map 字段以原始形态绑定、归一仍走 `readIntMap/readLongMap/readStringIntMap/readActivityTiers`；`storage.*` 不在模型（StorageSettings 本轮不迁移，仍走 FileConfiguration）；reloadAll 改 `settingsStore.reload()` |

**附带修复（迁移暴露的存量问题，非迁移引入）**：

- Union `lang/zh_CN.yml`、`lang/en_US.yml` 存在重复键 `union.reload.in-progress`
  （0.13.0 起 YamlMap S8 严格拒绝重复键；旧 snakeyaml 宽松 last-wins）。按旧语义
  保留后出现的条目、删除前一条。
- Union `config/spotbugs/exclude.xml` 两处类名仍为旧包 `io.pepper.union.*`（重构
  io→ltd 时漏改），导致预注册排除失效 → SpotBugs 报真实误报两例。改为 `ltd.pepper.union.*`。

**对抗性自审修复（delivery-review 后续轮）**：

- **发现 2（哨兵泄漏，真实缺陷，已修）**：`UnionSettingsModel` 的
  `name-pattern` 用哨兵 `\u0000name-pattern-unset` 区分"未配置→按 max-name-length 派生"
  与"显式设置"。哨兵本身是绑定语义，但会泄漏进 `Bindings.defaultsText`（未来默认文件
  再生成 / `ConfigFileStore.upgrade()` 补键的模板来源），把 NUL 哨兵串写进真实配置。
  修复：字段挂 `@Codec(NamePatternCodec.class)`——`toConfig` 把哨兵发射为
  `PluginSettings.DEFAULT_NAME_PATTERN`（默认 max-name-length=24 的派生正则），
  显式值透传；`fromConfig` 纯透传，绑定与哨兵判定行为完全不变。护栏测试
  `emittedDefaultsNeverLeakTheUnsetSentinel`（红→绿：修复前_FAIL，修复后_PASS）。
- **发现 6（运行接线零测试覆盖，已补）**：`PepperUnionPlugin.onEnable` 的
  `ConfigFileStore.load` 与 `reloadAll` 的 `settingsStore.reload()` 无任何测试直接覆盖。
  补 `UnionSettingsStoreWiringTest`（纯 JVM，`@TempDir` 数据目录 + 真实发布资源）锁：
  首跑 copy-once 材质化（保住 `storage.*`）、绑定无 ERROR、reload 失败保留旧快照 +
  ERROR issue（旧值取非默认 75 与默认 50 可区分，证明不是静默回默认）、reload 拾取
  磁盘合法修改。

**迁移层零 `org.bukkit.configuration` import 复核（三家 config 包）**：

```
ltd.pepper.treecut.config → ZERO
ltd.pepper.pepperminecart.config → ZERO
ltd.pepper.union.config → ZERO（StorageSettings / storage 包本轮明确保留 FileConfiguration）
```

### 16.3 ConfigMe 对照吸收（0.14.0）——类型系统 / 值级合法性 / 可插拔迁移

> **2026-09-10 完成**：对照文档 `docs/ConfigMe-vs-PepperLib.md`（AuthMe/ConfigMe v1.4.1
> master 源码逐项核对）。§7 四点借鉴中第 3 点（ValueWithComments 元素级随行注释）经
> 用户裁决**维持现状**（当前注释机制已覆盖同需求，见本节末"注释机制说明"）；第 1/2/4 点
> 三项对齐实现，验收 = 全仓 `:check` 绿（spotless/spotbugs/javadoc/japicmp）。

**① 类型系统扩展（对齐 ConfigMe 内建 Property 类型）**：`ConfigSchema.ValueType` 新增
`OPTIONAL/SET/ARRAY/TEMPORAL`，`Bindings` 的 `typeOf/typeZero/coerce/coerceElement/emit`
全链支持：

- `Optional<T>`：缺失/空 → 字段默认（缺省 `Optional.empty()`），存在即包裹，元素按泛型
  强制；发射时空 Optional → 空标量 `key:`（回读 null → empty）。**关键语义差异**：自研
  "缺失 → 字段默认"（与全系统一致），ConfigMe OptionalProperty 缺失 → `Optional.empty()`
  （字段默认仅在导出用）。文档化记录，不逐字复刻 ConfigMe 的"默认被忽略"。
- `Set<T>`：`LinkedHashSet` 保序去重（对齐 `SetPropertyType`），元素按泛型强制。
- `T[]`/基本类型数组：集合/数组 → 目标数组逐元素强制（对齐 `ArrayPropertyType` 跳过
  硬转失败元素）。
- `LocalDate/LocalTime/LocalDateTime`：多格式宽容解析（对齐 `TemporalType` 的多格式
  尝试 + 导出归一），另宽容 snakeyaml 2.6 把未加引号 ISO 日期解析为 `java.util.Date`
  的手写配置（按系统时区取本地日历）。发射走 ISO 文本 + 引号守卫。

**② 值级合法性信号（对齐 `PropertyValue.isValidInResource` 二元 → 三态）**：
`ConfigValues`（公共 API）+ `Bindings.loadWithValues → LoadResult<T>(model, values)` +
`ConfigFileStore.values()`。每条目 `PRESENT`（资源中存在且有效）/ `MISSING`（缺失，值 =
默认）/ `INVALID`（存在但不可用，已回落默认或夹紧）；`allValidInResource()` 对齐
`areAllValuesValidInResource`。**与资源配置正交**：按 §9.5，类型不符/缺失仍静默不记
issue——ConfigValues 是这类"静默回落"的机器可读通道，供迁移决策区分"缺键补默认"与
"值不合法须重写"。Optional 条目缺失视为 PRESENT（对齐 ConfigMe "absent optional 不触发
重写"）。

**③ 可插拔迁移服务（对齐 `MigrationService`/`PlainMigrationService` 显式接口）**：
`ConfigMigration`（`checkAndMigrate(Map root, ConfigValues values)`）+ `ConfigMigrations`
工厂（`noop` / `versioned` 包装既有 `ConfigVersions.migrate` / `versionedWithValidity`
= 版本步骤 + `!allValidInResource()` 任一触发）。`ConfigFileStore.load(..., ConfigMigration)`
重载 + `migrated()` 访问器；迁移在内存 root 副本执行、只塑造类型化模型。
**关键差异（有意保留）**：ConfigMe 的 `MigrationService` 返回 MIGRATION_REQUIRED 后由
`SettingsManagerImpl` **立即重建保存**（`isValidInResource=false` 即全文件重写）；自研库
始终不自动落盘（§8.3），`migrated()` 只标记"模型与磁盘分叉"，落盘仍显式
`save()`/`upgrade()`（putIfAbsent 不覆盖管理员值）——对齐动作只取"显式接口 + 值裁决"形态，
不取"自动重写"的激进语义。

**附带修复**：`YamlScalar` 引号守卫补漏——snakeyaml 2.6 仍解析 timestamp（`2026-01-10`
→ `java.util.Date`）与 sexagesimal（`12:34:56` → `Integer`），此前 string 值呈该形态会
明文发射、回读被隐式转型；`FORBIDDEN_PLAIN` 补两类模式强制加引号（时间字段发射随带）。

**注释机制说明（§7 第 3 点裁决：维持现状）**：ConfigMe 的 `ValueWithComments`（导出值绑
随行注释 + UUID 去重）服务于其**重建式写回**——注释必须"跟值走"才能落盘。自研库是**保真
式写回**（磁盘字节不动），元素级注释天然由磁盘文本保留，已有完整解析/修改链路：
`YamlComments`（解析归属：块注释 = 条目正上方 `#` 行、行内 = 同行尾部 `#`）→
`ConfigDoc.blockComments/inlineComment`（查询）→ `ConfigDoc.withComments`（字节保真修改）
→ `ConfigFileStore.setComments`（运行时 API + 即时重绑定）。无 `ValueWithComments` 形态
需求：导出值无需携带注释，因为没有"重建"这一步。详见交付文档。

### 历史：原 §16（ConfigLib 整合裁决，已被 §16.1 取代）

用户裁决（2026-09-09）：**绑定/schema 层不自研**（config 包内自研 ConfigSchema 计划退役），
采用 [Exlll ConfigLib](https://github.com/Exlll/ConfigLib)（MIT，活跃维护；本段以 v4.8.1 为准）。

### 实证（真实 jar v4.8.1 spike，本次实现前实测）

| 行为 | 实测结果 |
|---|---|
| `load()` | **只读**：不动盘、忽略未知键（可与家族工具组合） |
| `update()` | **整文件语义重写**：手写顶部/行尾注释丢失、排版规范化、**未知键被删除**；注释仅来自 `@Comment` 注解 |
| 空/纯注释文件 | `load` 抛 `ConfigurationException`（与家族 YamlMap「空文档→空 Map」语义不同，需材质化兜底） |
| 命名 | `NameFormatters.LOWER_KEBAB_CASE`：字段 `maxDistance` → 键 `max-distance` |
| 交付 | Maven Central `de.exlll:configlib-yaml:4.8.1`；GitHub release `configlib-paper-4.8.1-all.jar`（自含 relocate 的 snakeyaml-engine，Java 17 字节码） |

### 整合形态（混合，试点已按此落地）

- **ConfigLib 管**：`@Configuration` 注解绑定（字段默认值/`@Comment`/复杂类型/记录/Bukkit `ConfigurationSerializable`）、**只读 `load`**。
- **家族库仍管**（沿用 §7/§8/§9）：
  - 材质化 `saveDefaultConfig` / `ConfigFile.copyDefaultIfMissing`（保留发布注释）；
  - **YamlMap 预检 lint**（重复键/时间戳/BOM/多文档加固——ConfigLib 不提供）；
  - `ConfigVersions` 版本迁移（ConfigLib 无版本化）；
  - **写回一律走 YamlMerge/UpgradePatch**（字节保真 + 不删未知键 + 不覆盖），**常规加载路径不调用 `store.update()`**；
  - `IssueCollector`/`UnknownKeys` 校验与未知键告警（血缘自 treecut ConfigValidator）。
- **不做的组合**：除非某插件明确选择「schema 为唯一真相」的自我修复语义（此时可接受 `update()` 删未知键），否则禁止用 update 做升级补键。

### 提供方式

- 服务端 `ConfigLib-<ver>-all.jar` 作为前置插件（plugins/），消费方 `paper-plugin.yml` 声明
  `dependencies.server.ConfigLib: {load: BEFORE, required: true}`。
- 消费方编译：本地 `libs/configlib-paper-<ver>-all.jar`（compileOnly + testImplementation，自包含，
  与 PepperUnion-linkage 同模式）；或 Maven Central `de.exlll:configlib-yaml`（仅编译；测试/运行解析器
  需另备——推荐前者）。

### 试点

GlowingSquad `feat/configlib` 分支：`@Configuration SquadConfig`（`maxDistance`→`max-distance`）+ 只读 load
+ 3 个纯 JVM 绑定测试（kebab 映射/未知键不动盘/缺键回落 + 空文件异常文档化）全绿；全构建绿；已提交。

### 影响与后续

- ConfigSchema 自研计划退役（未实现，无遗留代码）。
- P3 迁移风格 = `@Configuration` 模型 + 家族工具（材质化 / YamlMap lint / ConfigVersions / YamlMerge 写回 / IssueCollector）。
- treecut 的多文件 profile 合并（extends/字典序/tag 展开/土壤家族）仍是领域层；config.yml 的绑定部分后续按此形态接。
- 风险：服务器新增 ConfigLib 前置 jar（升级集中一处）；ConfigLib 空文件语义需材质化兜底；
  若未来出现「任意值改写 + 注释保留」硬需求，okaeri 重估（§4）仍是最短路，`update()` 不满足字节保真。
