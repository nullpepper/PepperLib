# AuthMe ConfigMe × 自研 PepperLib 配置体系 —— 对照分析

> 对照对象：**AuthMe/ConfigMe**（独立库，`ch.jalu.configme`，v1.4.1，master 分支快照）
> 对照基准：**PepperLib 0.13.0 自研配置体系**（`ltd.pepper.lib.yaml` + `ltd.pepper.lib.config`，
> 设计文档 `docs/yaml-config-system-design.md`）
> 方法：ConfigMe 源码取自 `https://github.com/AuthMe/ConfigMe` master（135 个 main java 文件，
> 核心文件逐一阅读）；自研侧对照设计文档 §6–§10/§16 + 源码 API 面。
> 日期：2026-09-10

---

## 0. 一句话结论

**两家的目标与路线在"写回注释保留"上交汇，但走的是相反的实现路线：**
ConfigMe 用 **snakeyaml Node 全程重建**（从零 compose 出一棵带注释的节点树再 serialize，
磁盘化整为零）；自研库用 **磁盘文本逐字节保留 + 只增缺失块**（YamlMerge 文本切片合并，化整为零）。
由此派生出一整套能力/取舍差异。**没有任何一项能力是 ConfigMe 有而自研库缺的硬缺陷**；
反而在"管理员手改文件 + git diff 工作流"这一真实场景上，自研库的写回噪声控制显著优于 ConfigMe。
ConfigMe 值得借鉴的是其**类型系统与值转换的完备性**（13+ 内建 Property 类型、Bean/record 映射、
ConvertErrorRecorder 精细告警），以及 **MigrationService 的显式设计**。

---

## 1. 基础信息对照

| 维度 | AuthMe/ConfigMe | PepperLib 自研配置体系 |
|---|---|---|
| 定位 | 通用 Java 配置管理库（不绑定 Minecraft） | Minecraft 家族前置库内的配置子系统（也是通用组件） |
| 项目规模 | 独立仓库 AuthMe/ConfigMe，135 main java 文件，~1.4万行 | PepperLib 内 `yaml`+`config` 两包（引擎+工具链+绑定+存储） |
| 版本 | 1.4.1（2026-09 仍在维护，pushed 2026-09-08） | 0.13.0 |
| YAML 引擎 | snakeyaml 2.x（Node 级 compose/serialize，`processComments`） | snakeyaml 2.6（仅 compose AST 判键） + 自研文本切片合并 |
| 依赖 | snakeyaml + jetbrains annotations | snakeyaml 2.6（仅此一家，零新第三方） |
| 绑定方式 | **静态字段 Property 对象**（`SettingsHolder` + `Properties.newXxx` 工厂） | **注解字段**（`@ConfigModel`/`@ConfigPath`/`@ConfigComment`/`@ConfigRange`/`@Codec`） |
| 注释来源 | `@Comment` 注解 + `SettingsHolder.registerComments` 覆盖注册 | `@ConfigComment` + `@ConfigHeader` + 运行时 `ConfigDoc.withComments` |
| 写回路线 | **重建式**：Properties → Node 树（含注释）→ serialize 整文件 | **保真式**：磁盘文本原样 + YamlMerge 只插缺失键块 |
| 生命周期 | `SettingsManagerBuilder` → `SettingsManager`（load/validate/migrate/save） | `ConfigFileStore<T>`（load/reload/save/upgrade）+ `ConfigGroup` |
| 迁移 | `MigrationService`（显式编入流程） | `ConfigVersions`/`UpgradePatch`（版本迁移 + 升级补键） |
| 前置插件模式 | 无（纯库，消费方自由引用） | 是（PepperLib.jar 前置，消费方 depend/required） |

---

## 2. 绑定模型对照（核心差异）

### 2.1 ConfigMe：静态 Property 对象

```java
public class TitleConfig implements SettingsHolder {
    public static final Property<String> TITLE_TEXT = newProperty("title.text", "-Default-");
    public static final Property<Integer> TITLE_SIZE = newProperty("title.size", 10);
}
```

- 一个配置项 = 一个 `Property<T>` 对象（`IntegerProperty`/`StringProperty`/`BooleanProperty`/
  `DoubleProperty`/`LongProperty`/`FloatProperty`/`ShortProperty`/`EnumProperty`/`ListProperty`/
  `SetProperty`/`StringSetProperty`/`LowercaseStringSetProperty`/`MapProperty`/`ArrayProperty`/
  `InlineArrayProperty`/`RegexProperty`/`OptionalProperty`/`TemporalProperty`(LocalDate/Time/DateTime)/
  `CollectionProperty`/`BeanProperty`/`EnumSetProperty`——**>20 种内建类型**）。
- `Property` 接口：`getPath()`、`determineValue(PropertyReader)`（读值转换，可告警）、
  `isValidValue(T)`（程序化 setValue 的兜底校验）、`toExportValue(T)`（导出前转为基础类型）。
- 字段用 `public static final` 声明，`ConfigurationDataBuilder` 反射收集（含父类），
  按路径分组排序 + 唯一性校验（重复路径抛异常）。
- **Bean 映射**：`BeanProperty` + `@ExportName`/`@IgnoreInMapping`/record 支持，
  自定义类型经 `leafvaluehandler`（Enum/嵌套 Bean/Mapper）递归装配。

### 2.2 PepperLib：注解字段

```java
@ConfigModel
public final class MinecartConfigModel {
    @ConfigPath("take-off-mode") @ConfigComment({"...","..."}) TakeOffResult takeOffMode = TakeOffResult.INVENTORY;
    @ConfigPath("display-block-offset") @ConfigRange(min=0,max=16,clamp=true) int displayBlockOffset = 6;
    @ConfigPath("anvil-damage.chance-per-use") double anvilDamageChance = 0.12;
}
```

- 一个配置项 = 一个注解字段 + 字段初始化默认值（**默认值即声明在字段上**）。
- 类型体系：内建标量 + 枚举 + List + Map（0.13.0 起一等绑定）+ `@Codec` 自定义类型 + `@ConfigRange` 守卫。
- 领域归一（枚举回落、跨字段派生、材质集解析）放 **post-load（`ConfigPostLoad<T>`）** 或消费方工厂
  （Union `PluginSettings.from(model)`）。

### 2.3 对照要点

| 能力 | ConfigMe | PepperLib | 评注 |
|---|---|---|---|
| 声明式默认值 | `newProperty(path, default)` 每次构造 | 字段初始化器 | 等价 |
| 类型覆盖广度 | **更广**（>20 内建类型，含 Array/Set/Optional/Temporal/Bean/record） | 窄（标量/枚举/List/Map/Codec） | ConfigMe 的类型密度明显胜出；自研的 set 语义由 List+post-load 承担 |
| 自定义类型 | BeanProperty 递归映射（含嵌套） | `@Codec` 字段级 + post-load | 形式不同；ConfigMe 的"自定义类型为字段类型"更自然 |
| 约束/范围 | `IntegerProperty` 等各自类型校验 + `isValidValue` | `@ConfigRange`（min/max/clamp） | 自研更集中；ConfigMe `isValidValue` 校验更通用 |
| 键命名 | 显式 `newProperty("a.b")` 字符串 | `@ConfigPath`，缺省 kebab | 自研有缺省约定，ConfigMe 无 |
| 枚举 | EnumProperty（大小写语义在实现内） | 大小写不敏感（对齐旧行为） | 等价，细节自研更收敛 |
| 读取失败语义 | `determineValue` → `PropertyValue`（isValidInResource）+ `ConvertErrorRecorder` | `IssueCollector`（WARN/ERROR + action） | ConfigMe 告警更细（逐值/逐路径）；自研有 action 建议 |

---

## 3. 写回机制对照（最关键的路线分岔）

### 3.1 ConfigMe：Node 全程重建（重建式）

`YamlFileResource.exportProperties(configurationData)`：

1. 对每个 Property，`toExportValue(value)` 转基础类型（可为 `ValueWithComments`，携带随行注释）；
2. `PropertyPathTraverser` 按路径逐段构造 `SnakeYamlNodeContainer`（`getOrCreateChildContainer`）；
3. `SnakeYamlNodeBuilderImpl.createYamlNode` 把值 compose 成 snakeyaml `Node`
   （Scalar/Sequence/Mapping），注释收集齐后 `node.setBlockComments(commentLines)`；
4. 根节点 → `new Yaml(options).serialize(rootNode, writer)` 全量落盘
   （`DumperOptions`：BLOCK flow、`setProcessComments(true)`、UTF-8、缩进可配）。

- 写回 = **读入内存数据 + 程序持有的属性值 → 从零重建整个文件**。磁盘上的格式细节
  （缩进、引号风格、行尾对齐、空行）不保留，只保留注释文本。
- 运行时改值：`settings.setProperty(prop, value)` → 改内存 → `save()` 重建落盘。
- `ValueWithComments`：允许某个导出值**携带自己的注释**（用于 Bean/List 元素级，可选 UUID 去重）。

### 3.2 PepperLib：磁盘字节保真 + 只增缺失块

`YamlMerge.merge(diskText, templateText)`：

- 磁盘文本**逐字节不动**；用 snakeyaml compose 的 AST（键存在性 + 行号锚点）判定缺失键；
- 缺失键的整块（含前置注释）从默认模板**原文行切片**插入，按 section 层级重缩进；
- diff = **仅新增块**（自研 spike 实测：Node 往返对真实语料 60%~75% 行被重排，故裁决否决）；
- 运行期值/注释修改走 `ConfigDoc`（AST 行号切片局部改写，字节保真）；
- 写回只显式触发（`save()`/`upgrade()`/管理命令），常规加载绝不自动落盘。

### 3.3 对照要点

| 维度 | ConfigMe 重建式 | PepperLib 保真式 | 评注 |
|---|---|---|---|
| 写回后 diff | **噪声大**（全量重排：缩进/引号/对齐/折行均可能变） | **= 仅新增块**（既有字节零改动） | **自研胜**。AuthMe 生态容忍，因为其配置是"程序生成+注释固定"风格；家族插件是"管理员手改 + git diff"工作流（自研 §8.1 spike 实证否决 Node 往返，ConfigMe 恰是那条被否决路线的成熟工业实现） |
| 注释保留 | 保留（注释是公开一等公民，`setBlockComments`/`processComments`） | 保留（文本行切片，注释原样随块走） | 两边都达标；实现手段不同 |
| 运行时改值随行注释 | `ValueWithComments` 支持导出值绑注释 | `ConfigDoc.withComments` + `@ConfigComment` + `setComments` | ConfigMe 的"注释跟值走"对 Bean/List 元素级更精细 |
| 未知键处理 | 加载时**不写回**（默认唯一迁移触发保存），保留在内存忽略 | 未知键不动盘 + WARN（`UnknownKeys`） | 自研对未知键显式告警，更利于管理员自查 |
| 空白/空行/对齐 | 不保留（重建） | 原样保留 | 自研胜（§3.1 根因） |
| 大文件性能 | 每次 serialize 全文件 | 每次只碰缺失块/目标区 | 自研小块操作更优 |

> **重要澄清**：自研设计文档 §8.1 在 P1 做过 spike，**实测否决了"Node 级往返"**——ConfigMe 的
> 写回路线正是该候选路线的一种成熟实现。这不是"我们没想到"，而是"我们测过、按家族需求否了"。
> ConfigMe 能在 AuthMe 生态长期存在，说明其路线对"自动生成的样板配置"是够用的；只是不符合
> 家族插件"管理员手改 + 逐字节 diff"的硬约束。

---

## 4. 注释系统对照

| 维度 | ConfigMe | PepperLib |
|---|---|---|
| 属性注释 | `@Comment("...")` 注解（值为 String[]，`"\n"` 表空行） | `@ConfigComment`（String[]）+ 发射/材质化时写入 |
| 节（section）注释 | `SettingsHolder.registerComments(conf)` 里 `setComment(path, ...)` 手动注册 | 嵌套模型天然按 `@ConfigModel` 切节；注释挂字段上 |
| 文档头 | `CommentsConfiguration.setHeaderComments(...)` | `@ConfigHeader` + `ConfigDoc.header()/withHeader()` |
| 文档尾 | `setFooterComments(...)`（`FOOTER_KEY`，写文件末尾） | （无显式 footer API；可在模型外补键） |
| 运行时改注释 | 无专用 API（改注释须重写 SettingsHolder/重建） | **`ConfigFileStore.setComments(path, block, inline)` 运行时改 + 即时重绑定** |
| 注释归属解析 | 不复用磁盘注释（重建时注释来自注册表，磁盘旧注释被丢弃） | `YamlComments`/`ConfigDoc` 解析磁盘既有注释归属（块/行内） |

**关键差异**：ConfigMe 的注释是**注册表驱动**（注释文本存在内存 `CommentsConfiguration`，
写回时从注册表取，磁盘上管理员后加的注释在重建时**丢失**）；自研库的注释是**磁盘驱动 + 注解
双重来源**（`YamlComments` 解析磁盘已有注释 ↔ `@ConfigComment` 提供默认注释，`ConfigDoc`
保留既有条目注释）。→ 对"管理员在文件里补注释"这一动作，自研库保留、ConfigMe 不保留。

---

## 5. 生命周期 / 迁移对照

### 5.1 ConfigMe

```java
SettingsManager settings = SettingsManagerBuilder
    .withYamlFile(Path.of("config.yml"))
    .configurationData(TitleConfig.class)
    .useDefaultMigrationService()   // 缺属性即触发迁移 → 重建保存
    .create();
```

- `SettingsManagerImpl` 构造即 `loadFromResourceAndValidate()`：读文件 → 初始化全部值 → 若配
  `MigrationService` 且 `checkAndMigrate` 返回需迁移 → 立即 `save()`。
- `reload()` = 重新 load；`save()` = 重建落盘；`setProperty` 只改内存。
- `PlainMigrationService`：逐属性检查是否存在，缺失即整体触发"补键→重建"。
- `MigrationService` 接口允许自定义：改名/删属性/版本迁移等（`VersionMigrationService` 按版本）。

### 5.2 PepperLib

```java
ConfigFileStore<UnionSettingsModel> store = ConfigFileStore.load(
    UnionSettingsModel.class, dataFolder, "config.yml", classLoader);  // copy-once 材质化
store.reload();     // 失败保留旧快照
PluginSettings s = PluginSettings.from(store.get());
store.upgrade(v);   // 显式补键 + bak-v<旧版本> 备份
```

- `ConfigFileStore.load` 首跑 copy-once 材质化**发布资源**（带注释）；`reload()` 失败保留旧
  快照 + ERROR（绝不静默回默认）；`upgrade(schemaVersion)` 显式补键前备份；`save()` 原子写。
- `ConfigVersions`/`UpgradePatch`：版本迁移（内存）+ 补键（磁盘）。
- TreeCut 多文件走 `ConfigGroup`（profiles 合并等，领域层）。

### 5.3 对照要点

- **迁移触发策略**：ConfigMe 的 `useDefaultMigrationService()` **构造即检查、缺失即重建保存**
  （每次启动都可能改盘）；且 `PlainMigrationService` 对"**值存在但不合法**"（`areAllValuesValidInResource`
  为 false）同样触发迁移→重建保存（比"仅缺键补键"更激进）。自研库**默认不自动落盘**，只有显式
  `upgrade()`/命令才写。→ 自研更保守（家族规范 §8.3"写回绝不自动"），ConfigMe 更激进（开箱即迁移）。
- **reload 失败语义**：自研明确"失败保留旧值 + ERROR"；ConfigMe 抛 `ConfigMeException`（读坏
  文件会炸）。→ 自研对"运行中 reload"更稳健。
- 版本迁移：两边都有（`VersionMigrationService` vs `ConfigVersions`），形态接近。

---

## 6. 错误处理 / 校验对照

| 维度 | ConfigMe | PepperLib |
|---|---|---|
| 加载错误 | `ConfigMeException`（read/parse 失败即抛） | `YamlMap` 结构化 `YamlParseException` + store 层"失败保留旧值" |
| 值不合法的降级 | `determineValue` → 用默认值 + `ConvertErrorRecorder` 记录（是否有效在 `PropertyValue.isValidInResource`） | `IssueCollector` WARN + 回落默认/Clamp → `ConfigIssue`（path/level/action） |
| 程序化 setValue 校验 | `isValidValue` 兜底，非法抛异常 | `ConfigDoc.withValue` 标量发射校验 |
| 顶层非 Map | 抛 `ConfigMeException` | `YamlParseException`（具行列） |
| 未知键 | 加载忽略（不告警） | `UnknownKeys` WARN（列表化） |
| 重复键 | snakeyaml 2.x 默认行为 | S8 显式拒绝 + 结构异常 |

自研库在**结构化异常（行列）、未知键显式告警、运行时 reload 失败保旧**上更完备；ConfigMe 的
`ConvertErrorRecorder`/`PropertyValue.isValidInResource` 对"某个值合法但不在资源中"的表达更细。

---

## 7. 值得借鉴的点（对照后的后续候选）

> **2026-09-10 更新**：§7 四项已有裁决——第 1/2/4 项**已对齐实现**（lib 0.14.0，见
> 设计文档 §16.3），第 3 项**维持现状**（自研保真写回下注释天然保留，无需 ValueWithComments
> 的"导出值绑注释"形态；注释解析/修改机制用法见同期交付说明）。

1. **类型系统扩展**：~~ConfigMe 有 `OptionalProperty`/`SetProperty`/`ArrayProperty`/
   `TemporalProperty` 等成熟内建类型。自研目前用"List + post-load"承担 Set/Optional 语义。
   可考虑给 `ConfigSchema` 增加 Optional（可缺省为空）与 Set 的去重/无序语义，提升注解表达
   力（若家族出现需求）。~~ **0.14.0 已吸收**：`ValueType.OPTIONAL/SET/ARRAY/TEMPORAL`
   一等绑定全链（含多格式时间解析与 snakeyaml 日期宽容）；关键语义差异（Optional 缺失 →
   字段默认，而非 ConfigMe 的空 Optional）文档化记录。
2. **ConvertErrorRecorder 式的"值级合法性"表达**：~~自研 `IssueCollector` 已近，但
   ConfigMe 区分"值无效（用默认）"与"值有效但不在资源中（isValidInResource=false，可用于
   迁移决策）"。自研 MigrationService 决策可借鉴该二元（当前用"键是否存在"近似）。~~
   **0.14.0 已吸收**：`ConfigValues`（PRESENT/MISSING/INVALID）+ `allValidInResource()`，
   `ConfigMigration.checkAndMigrate` 可直接据值级信号裁决；类型不符/缺失仍静默不记 issue
   （§9.5），ConfigValues 即该类"静默回落"的机器可读通道。
3. **ValueWithComments 的元素级注释**：Bean/List 元素带注释（含 UUID 去重）。自研
   `ConfigDoc` 对 list 元素注释已保真，但"导出值绑注释"的 API 形态（annotation on export
   value）没有。**裁决维持现状**：该形态服务于 ConfigMe 的重建式写回（注释必须跟值走才能
   落盘）；自研保真式写回下元素级注释由磁盘文本天然保留，解析/修改链路已齐
   （`YamlComments` → `ConfigDoc.withComments` → `ConfigFileStore.setComments`）。
4. **节注释注册 API**：~~ConfigMe 的 `registerComments(conf)` 允许程序化注册任何 path 的
   注释（不依赖字段）。自研字段注解无法覆盖"非字段的节"注释（如空节 header）时，可补
   `ConfigFileStore.setComments` 已可完成，但缺一个"模型级静态注册"形态。~~
   **维持原结论**（运行时 `setComments` 已覆盖；未做模型级静态注册）。
5. **MigrationService 的显式接口**：~~ConfigMe 把迁移做成 `SettingsManagerBuilder` 的
   一等成员（自定义 `MigrationService` 实现改名/删键）。自研 `ConfigVersions` 是工具类而非
   可插拔接口，跨插件复用路径略陡。~~ **0.14.0 已吸收**：`ConfigMigration` 接口 +
   `ConfigMigrations` 工厂（`versioned`/`versionedWithValidity`）+ `ConfigFileStore` 集成
   （`migrated()` 标记）；**有意保留差异**：ConfigMe "返回需迁移即自动重建保存"，自研只标记
   "模型与磁盘分叉、调用方显式落盘"（§8.3 保守纪律）。

## 8. 结论

| 维度 | 胜者 |
|---|---|
| 写回布局噪声控制（admin 手改+diff） | **PepperLib**（Node 重建路线在自研 spike 中被实测否决） |
| 类型系统广度 / 自定义类型 | **ConfigMe**（>20 内建类型 + Bean/record 映射） |
| 注释保留（disk 既有注释 + 运行时改注释） | **PepperLib**（注释双来源 + setComments 运行时 API） |
| 迁移触发策略（保守 vs 激进） | **PepperLib**（显式触发；ConfigMe 构造即改写盘） |
| 运行时 reload 失败稳健性 | **PepperLib**（保旧 + ERROR；ConfigMe 抛异常） |
| 错误诊断结构化（行列/action） | **PepperLib** |
| 生态成熟度 / 文档 / 测试规模 | **ConfigMe**（长期生产验证，javadoc+wiki+demo+高覆盖） |

**总体**：架构路线的分岔（重建 vs 保真）决定了大部分差异，且自研库的路线选择有 spike 数据
背书、符合家族约束，无需动摇。ConfigMe 是"重建式"路线里做得最好的工业参考——我们的 §16.1
"Node 往返否决"记录与 ConfigMe 的长期存在并不矛盾，反而互相印证：**同一问题（注释保留）在
不同工作流约束下会得出不同最优解**。后续收益点集中在类型系统广度与时值校验表达（§7），
建议按家族真实需求择需吸收，不做全面对齐。

---

## 附录 A. 事实核查说明

本对照基于 **AuthMe/ConfigMe master 分支源码直接阅读**，关键断言与源码位置对应如下：

| 断言 | 源码出处 |
|---|---|
| 静态 Property 字段 + SettingsHolder 反射收集 | `configurationdata/ConfigurationDataBuilder.java`（`collectProperties`/`getPropertyField`） |
| 重建式写回：compose 节点 → serialize 整文件 | `resource/YamlFileResource.java` `exportProperties`（`createAndAddYamlNode`/`serialize`） |
| 注释来自内存注册表（磁盘旧注释不保留） | `SnakeYamlNodeBuilderImpl.collectComments`（仅 `configurationData.getCommentsForSection` + `@Comment` 注册表） |
| 构造即验证 → 需迁移即保存 | `SettingsManagerImpl.loadFromResourceAndValidate`（`checkAndMigrate == MIGRATION_REQUIRED → save()`） |
| 值不合法也触发迁移 | `migration/PlainMigrationService.checkAndMigrate`（`!areAllValuesValidInResource → MIGRATION_REQUIRED`） |
| 节点容器 / 路径遍历 | `resource/yaml/SnakeYamlNodeContainerImpl`、`resource/PropertyPathTraverser` |
| 内建 Property 类型清单（>20 种） | `src/main/java/ch/jalu/configme/properties/*.java`（目录枚举） |
| Bean/record 映射、@ExportName/@IgnoreInMapping | `beanmapper/` 包目录 |
| 注释 API：@Comment/setHeader/showFooter/ValueWithComments | `Comment.java`、`configurationdata/CommentsConfiguration.java`、`properties/convertresult/ValueWithComments.java` |
| 版本与维护信息 | `README.md`（groupId ch.jalu configme 1.4.1）、仓库 `pushed_at 2026-09-08` |

推断项（未逐行验证、标注为推断）：ConfigMe 在 AuthMe 生态长期生产使用（README/CI/Coveralls
迹象）；"管理员手改 + git diff 工作流"对 AuthMe 样板配置不敏感（其配置由 @Comment 程序化驱动）。
自研侧结论依据 `docs/yaml-config-system-design.md` §8.1 spike 数据与源码 API 面。