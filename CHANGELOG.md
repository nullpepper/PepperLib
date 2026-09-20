# Changelog

All notable changes to PepperLib are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本语义见
[README API 稳定性策略](README.md)。

## [0.16.0] - 未发布

### 新增

- `ltd.pepper.lib.world.SafeLandingSearch`：**安全落点搜索**（移植自 Residence 6.0.3.3
  `LocationUtil`，语义逐条对齐）。
  - `findOutside(Region, BlockReader, TargetFilter, Options, Random)`：在区域**四条边外侧**
    找可站立位置。取点顺序照搬官方 `getRandomEdge`（按 `iteration % 4` 轮转四条边 + 边上随机 +
    外扩 1 格）；落点判定照搬 `isValidLocation`（该格与上方一格**无碰撞箱**、下方一格非空且非岩浆）；
    纵向从区域顶部向下扫，先撞实体方块则本次失败。默认 15 次尝试（官方 `maxIt = 15`）。
  - `safeColumnAt(Location, BlockReader, minY, maxY)`：同一 XZ 上**就近**纵向找落点，
    先向下再向上（官方 `ResidencePlayerListener#getSafeLocation`），用于强制收伞后的救援落地。
  - `BlockReader` 把方块查询抽象出来（`passable` = 无碰撞箱、`isEmpty` = 就是空气、
    `typeAt`），使搜索逻辑可在**无服务端**环境下测试；生产用 `BlockReader.of(World)`。
  - `TargetFilter` 供调用方注入落点准入（领地插件在此检查目标位置的 `tp`/`move` 权限）。
  - **两处有意差异**（已写在类注释里）：① 本实现是**同步**的，官方走区块快照 + 异步调度；
    调用方须自行保证不在每 tick 路径上调用。② **不做出生点回退**——那是策略不是搜索，
    由调用方在收到空结果后决定（官方 `fallBackLocation` 回退到配置的 KickLocation 或世界出生点）。
  - 另：`safeColumnAt` 的纵向边界改用调用方传入的 `minY`/`maxY`（官方硬编码下界 0、
    上界 `getMaxHeight()`），使 y<0 的地层也能被搜到；常见地形下结果不变。

### 移除（死代码清理：全部经字节码引用扫描 + 源码 grep 双证零引用）

- `ConfigFileStore.loader` 私有字段：只在构造器写入、全仓库无任何读取
  （其唯一潜在作用——强引用 ClassLoader——已由 `model` 字段承担）；
- `ConfigPaths.join(List)`：同类的 `split`/`checkKey` 均在使用，仅 `join` 零调用；
- `ConfigSchema.Builder` 的 4 个零调用重载：`intField(String,int)`、
  `longField(String,long,Predicate)`、`doubleField(String,double,Predicate)`
  与 9 参 `field(...)`（保留的 2 参/3 参重载与 10 参 `field` 不受影响）；
- `ConfirmRegistry.clearExpired()` 的死局部 `now`（过期判断一直走
  `ConfirmEntry.isExpired()`）；`DocAst.entryOf(...)` 的死存储
  `String inline = null`（三个分支都会先赋值，改为延迟赋值）；
- aswm-provider `BukkitMemoryWorldService.unloadSync` 的死局部 `mvCoreClass`：
  `Class.forName` 探测调用保留（它是 `ClassNotFoundException` 分支的唯一来源），
  仅去掉未被读取的变量绑定；
- 测试代码：`ConfigFileStoreTest.materializesDefaultWhenFileMissing` 未使用的
  `defaultCopy`；`PapiExpansionSupportTest` 只写不读的 `server` 字段
  （`MockBukkit.mock()` 的全局副作用保留，仅不再持有返回值）。

> 零消费者公共 API（`ThreadGuard` 弃用静态壳、`GuiItemFactory`、
> `PapiExpansionSupport`、`ConfirmEntry.expiresAt()`、`SafeExpression` AST
> 访问器、`ConfigFileStore.contains/fileName/value`、`ConfigGroup.reloadAll/size`、
> `PageGuide`/`PageSession`/`WorldInstance`/`WorldProviderInfo` 的零调用成员等）
> 按 API 稳定性策略**保留未删**：它们是兼容性承诺面，删除属破坏性变更。

### 修复（`UnloadOptions` 契约在 aswm provider 真正落地）

- `BukkitMemoryWorldService.unloadInternal` 此前**完全忽略** `UnloadOptions`
  参数，`discardWhenEmpty()` 文档承诺的"世界仍有玩家即拒绝"从未生效（
  `WORLD_NOT_EMPTY` 错误码全生态零产生）。现在 `requireEmpty=true` 且实例世界
  仍有玩家 → 以 `WORLD_NOT_EMPTY` 拒绝卸载，实例保持注册、世界不卸载；
  `discardForShutdown()`（`requireEmpty=false`）不被该前置检查拦截，关服清理
  流程不因玩家在场而中断；
- `unloadSync` 此前丢弃 `Bukkit.unloadWorld` 的返回值并吞掉异常，会在卸载失败时
  照常注销实例、删除实例目录——即"假装已释放"（`WORLD_UNLOAD_FAILED` 因此长期
  是零引用死常量）。现在后端失败（返回 `false` 或抛异常）→
  `WORLD_UNLOAD_FAILED`，实例保留在注册表（状态回退 `ACTIVE`）供重试，磁盘清理
  推迟到后端确认卸载成功之后；
- `close()` 关服清理遇到上述失败时不再静默吞掉，改为记录失败实例（实例 id +
  原因）后继续清理其余实例。

## [0.15.0] - 2026-09-11

### 新增（record 形态模型的嵌套与默认来源：PepperClaim 迁移驱动，全链 additive）

- **record 模型支持嵌套与集合元素**（此前 record 路径只支持逐组件标量，嵌套 record 组件直接
  `IllegalArgumentException: unsupported config field type`；`List<record>`/`Map<String,record>`
  更糟——静默产出裸 `Map` 元素，留待调用方 CCE）：
  - record 组件为嵌套 `@ConfigModel` record → 按节平铺进 schema（子条目路径带前缀），绑定
    期用前缀整体构造，不再反射写 final 字段；
  - `List<@ConfigModel record>` 与 `Map<String,@ConfigModel record>` → 元素按元素自身 schema
    构造真 record（元素缺键回落该元素的默认来源），诊断并入同一 `IssueCollector`；
  - POJO（class）模型里的 record 字段同样支持：整体构造后写回字段（此前 class 模型 + record
    节 = `IllegalAccessException`，因为 record 字段不可反射改写）；
  - **未标注 `@ConfigModel` 的 record 元素在 schema 构建期报错**（点名键与类型），取代原来的
    静默裸 Map —— 旧行为会把类型错误推迟到运行期使用处才暴露。
- **`@ConfigDefaults`（新注解）**：标注在模型自己的 `static` 无参工厂方法上，绑定层用它取代
  "类型零值"作为 schema 默认值，并递归用于嵌套 record 节与集合元素。record 没有字段初值，此前
  缺键一律回落 `0`/`false`/`""`——对 `CoreSettings.defaults()` 这类集中式默认，会把缺失的
  `storage.host` 变成空串。方法须为 `static` 无参且返回模型类型，否则 schema 构建期报错。
- **损坏文件的回落改为出厂资源**：首跑遇到不可解析的文件时，store 过去用 `Bindings.defaultsText(model)`
  合成回落文本——对 `List<record>`/`Map<String,record>` 这类默认值只能吐出 `[BP[upTo=1000.0, ...]]`
  之类的 toString 文本（实测），回读时整条键降级为 INVALID。现在回落**逐字取用 classpath 出厂资源**
  （注释与取值都保留），资源自身不可解析时才退到合成文本。ERROR 通告与"不写盘"语义不变。
- 声明结果按类缓存（`ClassValue`）：schema/叶子/嵌套引用只依赖类结构，与磁盘无关。
- **`@Codec` 支持 record 组件**：`@Codec` 的 `@Target` 补 `RECORD_COMPONENT`，`ComponentMember.codec()`
  读组件上的注解。此前 `@Codec` 只标 `FIELD`，而 FIELD-only 注解在 record 组件上不可经
  `RecordComponent.getAnnotation()` 读到（实测），record 模型因此无法使用自定义序列化——金额
  "YAML 写元、模型存分" 这类换算只能靠调用方手工解析。`@ConfigPath/@ConfigComment/@ConfigRange`
  本来就是双 target，这次补齐一致性。
- **双路径装载**（`ConfigFileStore.load(model, dataFolder, fileName, resourcePath, loader[, postLoad[, migration]])`
  与 `ConfigFile.copyDefaultIfMissing(dataFolder, targetPath, loader, resourcePath)`）：数据目录文件名
  与 jar 内默认资源路径解耦。此前两者必须是同一个相对路径，默认资源只能放 jar 根目录；PepperClaim
  这类把默认文件放在 `defaults/` 子目录的插件无处落脚。现有重载行为不变（两者相同）。

### 兼容性

- 全链 additive：既有 POJO 模型（含嵌套 `@ConfigModel` class 节）与 record 标量模型行为不变，
  0.14.0 的 443 个测试全绿；新增 `BindingsRecordModelsTest`（13 例）覆盖三层嵌套 / 嵌套 class 节里的 record 节（属主链）/ 集合元素/默认
  来源/未标注元素快速失败/模板守卫。

## [0.14.0] - 2026-09-10

### 新增（ConfigMe 对照吸收：对照文档 `docs/ConfigMe-vs-PepperLib.md` §7 三项落地）

- **类型系统扩展**（对齐 ConfigMe `OptionalProperty`/`SetProperty`/`ArrayProperty`/
  `LocalDateProperty` 等内建类型，additive 一等绑定全链）：
  - `Optional<T>` 字段（`ValueType.OPTIONAL`）：缺失/空 → 字段默认（缺省
    `Optional.empty()`），存在即包裹；Optional 条目缺失在值级信号中视为 PRESENT
    （对齐 ConfigMe "absent optional 不触发重写"）。默认文件发射：空 → 空标量
    `key:`，有值 → 内值；
  - `Set<T>` 字段（`ValueType.SET`）：`LinkedHashSet` 保序去重（对齐 ConfigMe
    `SetPropertyType`），元素按泛型强制；发射 flow list；
  - `T[]`/基本类型数组字段（`ValueType.ARRAY`）：集合/数组 → 目标数组逐元素强制
    （对齐 ConfigMe `ArrayPropertyType` 跳过硬转失败元素）；发射 flow list；
  - `LocalDate`/`LocalTime`/`LocalDateTime` 字段（`ValueType.TEMPORAL`）：多格式
    宽容解析（LocalDate：`yyyy-MM-dd`/`dd.MM.yyyy`/`MM/dd/yyyy`；LocalTime：
    `HH:mm`/`HH:mm:ss`/`HH.mm`；LocalDateTime：ISO + 空格分隔 + 欧/美格式），
    另宽容 snakeyaml 2.6 把未加引号 ISO 日期解析为 `java.util.Date` 的情形
    （对齐 ConfigMe `TemporalType`）；发射 ISO 文本（引号守卫）。
  - `YamlScalar`/`Bindings` 的 `typeOf/typeZero/coerce/coerceElement/emit` 全链
    新增分支；record 组件同步支持。
- **值级合法性信号**（对齐 ConfigMe `PropertyValue.isValidInResource` 二元 → 三态）：
  - `ConfigValues`（公共 API）：每条目 `PRESENT`（资源中存在且有效）/`MISSING`
    （缺失，值 = 默认）/`INVALID`（存在但不可用，已回落默认或夹紧）；`allValidInResource()`
    对齐 `areAllValuesValidInResource`；`missingKeys()`/`invalidKeys()`；
  - `Bindings.loadWithValues(..., LoadResult<T>(model, values))` 重载；`ConfigFileStore`
    新 `values()` 访问器——"类型不符静默回落（§9.5 不记 issue）"的机器可读通道，
    供迁移决策区分"缺键补默认"与"值不合法须重写"。
- **可插拔迁移服务**（对齐 ConfigMe `MigrationService`/`PlainMigrationService` 显式接口）：
  - `ConfigMigration` 函数式接口 `checkAndMigrate(Map root, ConfigValues values)`；
  - `ConfigMigrations` 工厂：`noop()` / `versioned(currentVersion, steps)`（包装
    `ConfigVersions.migrate`）/ `versionedWithValidity(...)`（版本步骤 + 值不合法/
    缺失任一触发，对齐 `PlainMigrationService` 的 `!areAllValuesValidInResource`）；
  - `ConfigFileStore.load(..., ConfigMigration)` 重载 + `migrated()` 访问器：迁移在
    内存 root 副本上执行、只塑造类型化模型，**绝不自动落盘**（家族规范 §8.3 不变；
    与 ConfigMe"返回 true 即重建保存"的关键差异，落盘仍显式 `save()`/`upgrade()`）。

### 修复

- **`YamlScalar` 引号守卫补漏**：snakeyaml 2.6 仍解析 timestamp（`2026-01-10` →
  `java.util.Date`）与 sexagesimal（`12:34:56` → `Integer`），此前 string 值若呈这种
  形态会明文发射、回读被隐式转型（存量潜在 bug）；今在 `FORBIDDEN_PLAIN` 补两类
  模式强制加引号，时间字段默认文件发射随带。

### 变更

- 无破坏性变更（全部 additive：新 `ValueType` 四枚、`ConfigValues`/`ConfigMigration`/
  `ConfigMigrations` 新类型、`loadWithValues`/`LoadResult` 与 `load(..., ConfigMigration)`
  新重载、store 新访问器；`ConfigSchema.Entry` 内部 record 增加 temporalClass 字段属
  package-private 内核不进 japicmp 面）。API 稳定性策略保持（双模库前置插件模式）。
- 版本 0.13.0 → 0.14.0（前置插件 apiVersion 与发布坐标同步）。

## [0.13.0] - 2026-09-10

### 新增（P3 首批三家迁移支撑：TreeCut → Minecart → Union 配置层接入，设计文档 §16.2）

- **Map 一等绑定**（`ConfigSchema.ValueType.MAP`，`Entry` 增 `min/max/clamp` 全参数字段，
  `Builder.field` 全参数重载）：`Bindings` 的 `typeOf/typeZero/coerce/coerceKey/coerceValue/
  mapTypeArgs/emit` 全链支持嵌套 Map 逐行缩进发射（空 `{}`）。Union 限高/经验曲线、tier caps
  直绑或按需以原始 `Map<String,Object>` 绑定（逐条校验语义保留在域层）。
- **`ConfigPostLoad<T>` 装载后处理钩子**（函数式接口 `apply(T, IssueCollector)`）：
  `Bindings.load(Class, Map, IssueCollector, ConfigPostLoad)` 与
  `ConfigFileStore.load(Class, Path, String, ClassLoader, ConfigPostLoad)` 重载；
  store 的 read/commitDoc/reload 三径贯穿。
- **`@ConfigRange(clamp=true)` 夹紧模式**：越界修正到边界保留数值（clamp=false 回落默认值），
  复刻 Minecart 旧 `intInRange/doubleInRange` 夹紧语义。
- **NaN/Infinity 拦截**：INT/LONG/DOUBLE resolve 先 `Double.isFinite`，非有限 → WARN +
  回落默认（延续 S15；无范围声明时同样拒绝非有限值）。
- **枚举大小写不敏感**：`valueOf(...trim().toUpperCase(Locale.ROOT))`，对齐消费方旧
  `parseEnum` 大写化语义（Minecart TakeOffResult/ContainerPickupPolicy 等）。
- **`emit` 兄弟节 bug 修复**：旧 stack 逻辑无法处理同深度异键兄弟节（重复键）→ 重写为
  longest-common-prefix chain；新增 MultiSection / emitSeparatesSiblingSectionsAtSameDepth 测试。
- **`YamlScalar` flow map 发射**：`Map` 编码为 `{k: v}`（数字键明文），可作 flow list 元素，
  支持 Union `activity.tiers` 默认形态的模型出货默认文件。

### 变更

- 无破坏性变更（全部 additive：新 `ValueType.MAP`、新重载、新接口 `ConfigPostLoad`、
  `@ConfigRange` 新属性 `clamp`）。API 稳定性策略保持（双模库前置插件模式）。

## [0.12.0] - 2026-09-09

### 新增（自研配置体系：替代 ConfigLib 绑定层，设计文档 §16 裁决反转）

- **注解驱动绑定** `ltd.pepper.lib.config`（公共 API，纯 JDK）：
  - `@ConfigModel` / `@ConfigPath` / `@ConfigComment` / `@ConfigRange` 注解族；
  - `Bindings`：注解模型 → 内部 schema → 类型化装载（POJO/record、嵌套 `@ConfigModel` 节、
    枚举回落、范围校验 WARN）与默认文件发射（kebab 路径 + 注释 + 正确引号）、模板守卫；
  - `ConfigSchema` / `SchemaValues` / `SchemaTemplateGuard`（package-private 内核，附
    `ConfigSchemaTest` 红绿驱动；原退役计划残留测试已修正转绿）。
- **注释操作**（核心新能力）：
  - `YamlComments`：解析 YAML 文本 → 各条目的**归属注释**（条目标正上方的块注释 + 同行尾部
    行内注释；按点号路径查询、全量枚举）；
  - `ConfigDoc`：注释感知文档，`withValue` / `withComments` 对条目执行**字节保真**修改
    （只动目标区，其它字节逐字不变；复用 YamlMerge 的 AST 行号切片路线）；
  - `YamlScalar`：最小标量发射器（数字/布尔/枚举/空值明文，字符串按 YAML 规则加引号，
    List → flow list）。
- **运行时存储**：
  - `ConfigFileStore<T>`：首跑材质化默认文件（copy-once 带注释）→ 注释感知文档 + 类型化
    快照；`set`/`setComments` 即时重绑定、`save` 原子落盘、`reload` 失败保留旧快照、
    `upgrade` 升级补键（putIfAbsent + 备份 + 注释保留）；
  - `ConfigGroup`：多文件聚合（saveAll/reloadAll）。

### 新增（Configurate 能力对齐增量，0.12.0 同版本追加）

- `ConfigDoc.children(path)`/`Entry`（name/value/section）：**子节点遍历**（根或任意节，文档序）；
- `ConfigDoc.mergeDefaults(path, Map)`：**节内 putIfAbsent 默认合并**（仅插缺失键、嵌套节整块渲染，字节保真）；
- 文档头注释：`ConfigHeader @ConfigHeader`（类级，默认文件发射头部 `#` 注释）+
  `ConfigDoc.header()`/`withHeader(List)`（读写字节保真）；
- 自定义类型序列化注册点：`ConfigCodec<T>` 接口 + `Codec @Codec`（字段级）——标量/流转义
  往返，非法输入装载回落默认（与 Values 语义一致；codec 输出 Map 暂不支持默认发射）。

## [0.11.0] - 2026-09-07

### 新增

- **写回合并器** `ltd.pepper.lib.yaml.YamlMerge`（设计文档 §8）：文本模板合并——磁盘 + 默认模板
  → 合并文本（只增缺失键块含前置注释，磁盘其它字节**原样不动**，putIfAbsent 语义）；
  AST 行号锚点 + 源文本切片，金样测试逐字断言。**路线裁定**：Node 往返（compose→改→serialize）经
  真实语料 spike 否决（config.yml +46/−44、profiles +15/−12、+13/−9 行重排），文本模板合规定案。
- **配置文件工具链** `ltd.pepper.lib.config`（设计文档 §7/§9）：
  - `ConfigFile`：首跑落盘（copy-once，注释安全）/ `readUtf8`（剥离 BOM）/ `writeAtomic`（temp+rename，崩溃一致）；
  - `ConfigIssue` / `IssueLevel` / `IssueCollector`：校验问题收集（ERROR/WARN + 点号路径）；
  - `Values`：类型化读取（bool/int/long/double/string/list/enum，与 PepperTreeCut 助手同形，静默回落）；
  - `UnknownKeys`：单层未知键检测（treecut checkKeys 泛化）；
  - `ConfigVersions`：configVersion 读取 + 链式迁移（防死循环护栏）；
  - `UpgradePatch`：升级补键薄层（plan 纯内存 / apply 出合并文本）。

## [0.10.0] - 2026-09-07

### 新增

- **YAML 统一解析门面** `ltd.pepper.lib.yaml`（设计文档 `docs/yaml-config-system-design.md` §6）：
  - `YamlMap.parse`：YAML 文本 → 保序 `Map<String,Object>`（纯 JDK、零 Bukkit 依赖）；
    SafeConstructor 钉死、禁 timestamp、重复键报错（显式收紧，snakeyaml 2.6 默认放行）、
    空文档/纯注释归一空 Map、多文档拒绝、BOM 剥离；
  - `YamlParseException`：结构化错误（1-based 行列 + snakeyaml 原问题描述，中文文案由消费方组装）；
  - 语义矩阵测试逐行锁定（S1–S16 定案表）。
- **依赖显式化**：`org.yaml:snakeyaml:2.6` 进 `libs.versions.toml`（此前 LanguageBundle 靠 paper-api
  传递隐式获得）——运行期由服务端捆绑提供，lib 不打包不传递。

### 变更

- **i18n LanguageBundle 收敛**：两处裸 `new Yaml().load()` 换 `YamlMap.parse`（行为护栏：现有
  i18n 测试全绿；宽容降级语义保留在调用点；副产物修复：日期样字符串不再隐式变 `Date`、
  顶层非映射文件从静默忽略升级为 warn）。

### 新增

- **通用 GUI 设施下沉**（来源：PepperUnion gui 包，跨插件单一来源）：
  - `ltd.pepper.lib.gui.PageGuide<T>`：菜单分页器（内容槽/上下页按钮/页码
    信息槽/去事件化翻页 `handlePageSlot`/`setItemTransformer`；页码计算复用
    `Pagination.pageCount`）；
  - `ltd.pepper.lib.gui.GuiKit`：箱子 GUI 物品/文本构造（pane/namedItem/
    legacy/contentSlots）；
  - `ltd.pepper.lib.gui.PageSession`：菜单会话基类（玩家引用/库存/渲染上下文
    守卫/回主线程/异步槽位刷新 `refreshSlot`/安全发消息 `send`）。

## [0.8.0] - 2026-08-25

### 新增

- **可持久化泛型键值存储** `ltd.pepper.lib.persist`（双消费者共设计：
  PVP 竞技场的地图/模板配置与比赛结果持久化 + 漂流瓶插件类似需求）：
  - `PersistentStore<K, V>`：构造时全量加载（文件缺失 → 空表；损坏 →
    `IOException` 构造失败即暴露）；`put`/`remove` 修改异步写盘（写中合并、
    单飞——写盘期间的修改并入下一次写）；`flush()` 同步落盘兜底
    （关闭/检查点防丢最后一批修改）；`snapshot()` 不可变快照；
  - 原子写崩溃一致：先写同目录临时文件再原子 rename（不支持原子移动的
    文件系统退化为普通替换，仍无中间态写入目标文件）；异步写失败保留
    dirty 状态（下次修改重试）+ 日志；
  - `StoreCodec<K, V>` 整表序列化接口（纯 JDK 零第三方依赖，Gson/Jackson
    由消费者注入实现）；`PersistentStores.fileBacked(file, codec, executor)`
    工厂（executor 须串行，建议单线程池）。
  - 测试：写入落盘/回读回环、加载回环、remove、异步突发合并（20 次连续
    修改最终一致落盘）、原子写无临时文件残留、损坏文件加载抛异常、
    snapshot 不可变、flush 幂等。

> 提取纪律案例：双消费者（PVP 竞技场 + 漂流瓶）确认同构需求，以 Experimental
> 状态进入；两消费者接入并上线后转正冻结。

### 变更

- 版本 0.7.0 → 0.8.0（前置插件 apiVersion 与发布坐标同步）。

## [0.7.0] - 2026-08-25

### 新增

- **世界实例能力（Experimental）** `ltd.pepper.lib.world`（双消费者共设计：
  PVP/PVE 竞技场插件，语义一致「结束 → 清场 → 卸载实例」）：
  - `InstanceWorldService`：与具体 Slime 实现解耦的异步 SPI（`create` / `find` /
    `instances` / `unload` / `unloadAll`），模板读取异步、Bukkit 操作回主线程；
    失败经 `WorldProviderException` + 稳定错误码 `WorldProviderError`（8 码）表达，
    provider 缺失返回 `PROVIDER_UNAVAILABLE`，不降级为普通 Bukkit 世界；
  - `WorldTemplateRef`（id + 绝对路径，id 限 `[a-z0-9_-]+`）/ `WorldInstanceRequest` /
    `WorldInstance`（五态状态机）/ `UnloadOptions`（业务卸载 `discardWhenEmpty`
    与关闭清理 `discardForShutdown` 两种语义）/ `WorldProviderInfo`；
  - 能力标注 `@MinMinecraftVersion("1.18", "world-instance")`（
    `PepperLibRuntime.CAP_WORLD_INSTANCE`），守卫测试防漂移；
  - 部署契约：仅前置插件模式（shade 消费者因类重定位无法经 ServicesManager 互通）。
- **可选 provider 子项目 `pepper-lib-aswm-provider`**（独立薄 jar，非 PepperLib.jar
  一部分）：基于唯一公开发布产物 `com.infernalsuite.aswm:api:3.0.0`
  （Advanced Slime Paper API），`AdvancedSlimePaperAPI.instance()` 运行时探测，
  不可用则自禁并诊断；模板单文件只读 `SlimeLoader` + 内存模板缓存
  （`SlimeWorld.clone` 每实例一份活体世界）；三重防落盘
  （readOnly + `unloadWorld(save=false)` + autoSave 关闭）；11 项 MockBukkit
  测试覆盖创建/查询/卸载/冲突/失败清理/关闭语义。
  > 依赖说明：aswm-api 的 POM 传递依赖 flow-nbt 2.0.2 仅发布于已失效的 rapture
  > 仓库，本工程排除该传递依赖并以 Maven Central 的 flow-nbt 1.0.0 满足 javac
  > 签名解析（工程零 flow-nbt 代码，运行时由 ASP 环境提供）。

> 提取纪律案例：本能力为双消费者共设计（规格一致、代码未先存在），非「两插件
> 已有一致语义」的提取——按 README 纪律以 Experimental 状态进入，两插件接入
> 并上线后转正冻结（避免 GuiItemFactory 零消费者 Experimental 的前车之鉴）。

### 变更

- 版本 0.6.0 → 0.7.0（前置插件 apiVersion 与发布坐标同步）。

## [0.6.0] - 2026-08-20

### 新增

- **版本比较工具** `ltd.pepper.lib.runtime.LibVersions`（设计评审 §4.3）：
  - `parse`：semver 风格 1~3 段版本（`"0.5.0"` / `"0.5"` / `"1"`）解析为 `[major, minor, patch]`
    数值数组，缺段补 0；非法输入（null/空白/非数值/空段/4 段以上/负数）抛
    `IllegalArgumentException`；
  - `atLeast(actual, minimum)`：分段数值「实际 &gt;= 最低」比较（缺段按 0 补，
    `"0.5.0"` 与 `"0.5"` 等价）。
- **运行时「≥ 最低版本」校验**：`PepperLibRuntime.atLeast(String)` default 方法
  （委托 `LibVersions`）——替代消费者侧 `apiVersion().startsWith(...)` 前缀匹配：
  0.x 阶段前缀近似 minor 契约，1.0 冻结后破坏性 major 升级会被前缀误放行。
  兼容注意：default 方法为纯增量（japicmp），但旧版 lib 运行时不存在该方法，
  前置模式消费者升级 lib 与消费者必须同步（lib 先行）。

### 变更

- 版本 0.5.0 → 0.6.0（前置插件 apiVersion 与发布坐标同步）；japicmp 基线切至 0.5.0。
- 消费者版本契约升级为「版本单一来源注入 + atLeast 校验」（Union/Claim/BindManager/
  CustomMessage 同步切换，见各消费者提交）。

## [0.5.0] - 2026-08-20

### 新增

- **安全表达式引擎** `ltd.pepper.lib.expression`（源自 PepperBotCustomMessage 提取，
  纯 JDK 零依赖）：
  - `SafeExpression`：白名单布尔表达式引擎（`&&` / `||` / `!` / 比较 /
    `contains` / `startswith` / `endswith`，字符串/数字/布尔字面量）——无反射、
    无类加载、无方法调用，源码长度（4096）与嵌套深度（64）有界，拒绝 RCE 面；
  - `PlaceholderVariableMapper`：条件串中 `<xxx>` 与 `%xxx%` 改写为合法变量名
    （非法字符 → `_`，清洗后同名冲突抛 `IllegalArgumentException`），
    记录 变量名 → 原始占位符名 映射。
- **通用纯 Java 工具** `ltd.pepper.lib.util`（源自 PepperBotCustomMessage 提取）：
  - `CooldownTracker`：per-key 冷却槽——`tryAcquire` 原子抢占、`release`
    remove-if-equals（防并发误删）、`shouldSendTip` 提示节流、`remainingMillis`、
    `clear`；窗口 `windowMillis <= 0` 视为禁用（直通语义与原版一致）；
  - `Hashing.sha256`：UTF-8 → 64 位小写 hex。

> 例外条款案例：表达式/工具四件套为单消费者（CustomMessage）提取，按 README
> 定位例外条款允许——防 RCE 安全原语属高价值稀缺能力，预期第二消费者。

### 变更

- 版本 0.4.0 → 0.5.0（前置插件 apiVersion 与发布坐标同步）；BindManager
  `REQUIRED_PEPPERLIB_API` 同步升级 0.5；japicmp 基线切至 0.4.0。

## [0.4.0] - 2026-08-20

### 新增

- **一次性验证码服务** `ltd.pepper.lib.verification.OneTimeCodeService`（源自
  PepperBotBindManager `VerificationManager` 提取，0.4.0）：
  - 泛型负载 `issue(payload, ttl)` / `consume(code)`（原子消费，并发同码只成功一次）/
    `peek`（非破坏性查看）/ `restore`（失败回滚放回）/ 每键冷却（`tryAcquireCooldown`
    原子获取 + `putCooldown` 无条件重置）/ `cleanupExpired` / 设置热替换
    （`updateSettings`，进行中验证码与冷却不丢失）；码长 [4,8]、秒数钳位。
  - `OneTimeCodeServiceConcurrencyTest` 随迁（同码并发单胜者、冷却单放行）。
- **JDBC 工具** `ltd.pepper.lib.storage`（源自 BindManagerImpl 提取，纯 JDK 零依赖）：
  - `SqlExceptions`：唯一键冲突（SQLState 23xxx / 消息兜底）与 transient busy
    （errorCode 5 / sqlite_busy / database is locked）分类；
  - `JdbcRetry.withConnectionRetry`：有限次退避重试（只重试 busy，唯一键冲突
    立即上抛；默认 3 次 / 50ms，可自定义）。

- **自适应加载**（docs/pepperlib-dual-loading-and-consumer-migration.md §4.1）：
  - `ltd.pepper.lib.runtime.ServerVersions`：服务器版本解析/比较纯函数——新旧格式
  - `PepperLibRuntime.CAP_GUI_HOST`（`"gui-host"`）能力常量：前置插件 `onEnable`
    按 `Bukkit.getMinecraftVersion()` 自动决策能力集（plugin 包内 `CapabilityResolver`
    纯函数）；低于 1.21 的服务器不声明 gui-host 并输出 warning——`GuiHolder` /
    `GuiHost` / `PageHolderAdapter` 的公共签名引用 `InventoryView`（1.20.x 为 class、
    1.21+ 为 interface，形态不匹配的运行时执行抛 `IncompatibleClassChangeError`）。
  - 前置插件描述符 `paper-plugin.yml` → `plugin.yml`（`api-version: '1.18'`）：
    Paper 26.x 对 paper-plugin.yml 有 api-version 下限校验（`1.18 too old`），
    plugin.yml 的 `'1.18'` 在 1.18.2 ~ 26.x 全区间被真实服务器验证可加载。
  - `onEnable` 改用 `getDescription().getVersion()`（`getPluginMeta()` 是 Paper
    1.19.4+ API，1.18.2 上不存在）。
  - CI 新增 `legacy-consumer-paper-smoke` job：Paper 1.18.2 + Java 17 真实服务器
    验证自适应加载（`scripts/paper-smoke.sh legacy` 模式）。
  - **注解驱动能力决策**：新增 `ltd.pepper.lib.runtime.MinMinecraftVersion` 类级注解
    （`value` 最低版本 + `capability` 能力名，只表达下限）；`GuiHolder` /
    `GuiHost` / `PageHolderAdapter` 标注 `@MinMinecraftVersion("1.21", "gui-host")`；
    前置插件 `CapabilityAnnotationScanner` 扫描 classpath（jar/目录两种形态，零依赖）
    聚合「能力 → 最低版本」注册表，`CapabilityResolver` 改为注册表遍历决策——
    新增特性只需贴注解，不再改决策代码；消费者 `supports("gui-host")` 契约不变。
    守卫测试（`MinMinecraftVersionGuardTest`）断言 gui-host 三类注解存在且阈值一致。

### 变更

- 版本 0.3.0 → 0.4.0（前置插件 apiVersion 与发布坐标同步，单一来源根项目
  version）；BindManager 依赖与 `REQUIRED_PEPPERLIB_API` 同步升级 0.4。

### 构建

- 构建基础设施集中化：`gradle/libs.versions.toml` 版本目录 + `buildSrc` 约定插件
  （`pepper.java-conventions`：toolchain 25 / `--release` 17 / 依赖解析 JVM 属性钉回 /
  公共仓库 / JUnit5；`pepper.spotless`：importOrder + palantirJavaFormat）——
  三个子项目 `build.gradle.kts` 去重，依赖与插件版本单一来源（升级只改目录一处）。
- `japicmp` 二进制兼容门纳入 `./gradlew check` 绿门：基线默认 mavenLocal 上一发布
  版本（0.4.0），`PEPPER_LIB_BASELINE_JAR` 可覆盖；基线 jar 缺失时跳过并告警
  （fresh 环境/CI 无本地发布历史）。
- 新增 `BuildInfraGuardTest` 守卫测试：`check` 必须依赖 `japicmp`；三个构建文件
  不得散落硬编码版本。

## [0.3.0] - 2026-08-20

### 新增

- **第三个消费者接入（前置模式）**：PepperBotBindManager 仿照 PepperClaim 前置模式
  接入——`compileOnly` 坐标依赖 + `plugin.yml` 声明必需前置 + `onEnable` 经
  ServicesManager 校验 `PepperLibRuntime`（`REQUIRED_PEPPERLIB_API`），复用 storage
  迁移框架 / task ThreadGuard / validation Preconditions。

### 变更

- 版本升至 0.3.0（前置插件 apiVersion 与发布坐标同步）。

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
