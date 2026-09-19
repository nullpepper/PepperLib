# 配置文件前置库 —— 各 Minecraft 插件项目配置现状调研

> 调研时间：基于工作区当前代码快照
> 目标：为「制作配置文件前置库」（统一配置技术栈、下沉共享）摸底各项目配置文件现状。
> 依据：各项目 `src/main/resources` 下的默认配置文件 + 各 `config`/`conf` 包的加载代码。

## 1. 总体结论

工作区内的 Minecraft 插件可分出**两个生态**：

- **Pepper 主家族**（联手 PepperLib 前置插件）：PepperClaim、PepperUnion、PepperTreeCut、
  GlowingSquad、PepperMinecart、PepperTrashBin、PepperPvpArena、UserPrefix——这是配置前置库的
  **目标作用域**。
- **PepperBot 机器人插件群**（pepperbot/ 独立模块，各自自研配置装载）：PepperBotCore /
  PepperBotBindManager / PepperBotChatSync / PepperBotCommandDispatcher / PepperBotCustomMessage /
  PepperBotPlugin——自带 config 装载器，**目前不在家族统一蓝图范围内**，可作后续候选。

主家族目前存在 **4 种互不相通的配置技术**（叠加在同一个家族内重复建设）：

| # | 技术 | 使用插件 | 特征 |
|---|---|---|---|
| A | 手写 Bukkit `YamlConfiguration` 解析 + 值对象 | PepperClaim（多文件 SPI）、PepperUnion（手工 getter → record）、PepperMinecart（PluginConfig） | 点号路径语义、每次加载补默认并 `disk.save()`（毁注释）、无法 JVM 单测 |
| B | PepperLib 纯 JDK 门面（`ltd.pepper.lib.yaml` + `ltd.pepper.lib.config`） | PepperTreeCut（已迁移） | 单层键取值、issue 收集、configVersion 迁移、注释保留写回；是前置库的既定引擎 |
| C | Jackson YAML Bean（snake_case） | PepperTrashBin（ConfigManager + RootConfig 等 Bean） | 字段→键自动映射、可序列化回写、原子写 |
| D | 第三方注解绑定框架 | GlowingSquad（[Exlll ConfigLib](https://github.com/Exlll/ConfigLib) `@Configuration`，试点已落地）、UserPrefix（Carm `cc.carm.lib.configuration`） | 注解绑定字段/默认值/注释，绑定层不自研 |

`PepperLib/docs/yaml-config-system-design.md`（2026-09 更新）已把统一方案与分期写死：
**L0 引擎（YamlMap/YamlMerge）+ L1 生命周期（ConfigFile）+ L2 工具链（Values/IssueCollector/
ConfigVersions/UpgradePatch/UnknownKeys）下沉 lib；绑定层采用注解自研（§16.1，0.12.0 落
地——原「采用 Exlll ConfigLib」裁决同日反转）；逐家迁移（P3）**。前置库的实际形态 =
**PepperLib.jar（引擎+工具链+自研绑定/注释/运行时存储）** 一个前置插件，消费方
`paper-plugin.yml` 声明 `PepperLib` 为 `load: BEFORE, required: true`。

> **外部参考对照**：`PepperLib/docs/ConfigMe-vs-PepperLib.md`（2026-09-10）——与
> AuthMe/ConfigMe（`ch.jalu.configme`，注释保留配置库的另一个工业实现）逐项对照。
> 结论：两库在"写回注释保留"上交汇但路线相反（ConfigMe 重建式 vs 自研保真式）；自研路线
> 有 §8.1 spike 数据背书；ConfigMe 值得借鉴的是类型系统广度（>20 内建 Property 类型、
> Bean/record 映射）与值级合法性表达。详见该文档。

---

## 2. 现状矩阵（主家族）

| 插件 | 配置文件（默认资源） | 加载技术 | 读写模式 | 依赖声明 | 迁移状态 |
|---|---|---|---|---|---|
| **PepperLib** | —（库本身） | `yaml`/`config` 包（纯 JDK） | 只读门面 + 显式写回 | 前置插件 `PepperLib.jar` | P0/P1 完成（0.10.0 / 0.11.0） |
| **PepperTreeCut** | `config.yml`（v2）+ `profiles/10-vanilla.yml`、`20-custom.yml` + `lang/zh_cn.yml`、`en.yml` | PepperLib `Values`/`YamlMap` + 领域 `ConfigLoader`/`ConfigValidator`/`ConfigMigrator`/`ConfigHolder` | 只读；issue 校验；`ValidationReport` 门禁（全 profile 禁用才 fatal） | paper-plugin.yml：`PepperLib BEFORE required` | **P2 已完成**（0 处 `org.bukkit.configuration`） |
| **PepperClaim** | `defaults/` 9 份：`config.yml`、`worlds.yml`、`tracks.yml`、`slots.yml`、`economy.yml`、`prosperity.yml`、`flags.yml`、`claim-config.yml`、`visuals.yml`；`lang/zh_CN.yml`、`en_US.yml` | `ConfigSource` SPI + `BukkitYamlSource`（复制 defaults → 缺键补默认 → **每次加载 `disk.save()`**）→ 不可变 `Settings` record + 8 个 settings record | 多文件；**运行期自动写回（对象往返毁注释）**；`diffLog()` 通告 | paper-plugin.yml：`PepperLib BEFORE required` | **P3 待迁移**（1 处 Bukkit config：BukkitYamlSource） |
| **PepperUnion** | `config.yml`（123 行，域分节）+ `lang/zh_CN.yml`、`en_US.yml` + `trchat/guild-channel.yml` + `union-version.properties` | `PluginSettings.from(FileConfiguration)` 手工 getter → 域 record（Guild/Account/Chat/Pvp/Language/Activity） | 只读 | paper-plugin.yml：`PepperLib BEFORE required` | **P3 待迁移**（3 处 Bukkit config：PluginSettings / StorageSettings / GuildConfigType 的 ITEM 序列化） |
| **GlowingSquad** | `config.yml`（1 键：`max-distance: 128`） | **PepperLib 自研注解绑定**（0.12.0 起）：`@ConfigModel SquadConfig` + `@ConfigPath("max-distance")` + `ConfigFileStore` 只读装载；注释随默认文件材质化 | 只读；空文件回默认（优于原 ConfigLib 空文件抛异常） | paper-plugin.yml：`PepperUnion`+`packetevents`+**~~ConfigLib~~（已除名）** 均 BEFORE required（+PepperLib 前置提供类） | **P3 试点完成**：原 ConfigLib 试点已反向迁移至自研体系（仅剩 `GlowToggleStore` data.yml 是数据文件，超出范围） |
| **PepperMinecart** | `config.yml`（72 行，约 22 个设置） | 手写 `PluginConfig`：从 `FileConfiguration` 直读，自带 `intInRange`/`doubleInRange`/`checkedList`/`parseEnum` 归一助手 | 只读；`/pm reload` 热重载 | **未依赖 PepperLib** | **P3 待迁移**（简单档，1 处 Bukkit config） |
| **PepperTrashBin** | `config.yml`（Jackson 序列化产出） | Jackson YAML Bean：`ConfigManager` + `RootConfig`/`SettingsConfig`/`MessagesConfig`/`StorageConfig`/`BinDefinition`/`AbuseConfig`；`normalizeNulls`/`normalizeRanges`、原子写 | 混合（首次创建写回；加载失败保留旧配置） | **未依赖 PepperLib**（`softdepend` 仅 PAPI/Vault） | **P3 待迁移**（中档；0 处 Bukkit config，换源为家族引擎） |
| **PepperPvpArena** | **无 config.yml**；`config` 包为占位（package-info 声明） | `ArenaMapLoader`：读写 `maps/<id>.yml`（**数据文件**） | 数据文件读写 | plugin.yml：`depend: PepperLib` + softdepend WorldEdit/PepperUnion/ASWM-Provider | **P3 边界待裁决（OQ-5）**：config.yml 尚无 → 落地时直接按新体系建 |
| **UserPrefix** | `plugin.yml`（命令/权限）+ `prefixes/example-prefix.yml` + `i18n/`（前缀示例）+ `PLUGIN_INFO` | **Carm MineConfiguration**：`PluginConfig implements Configuration`（静态 `ConfiguredValue`/`ConfiguredItem`/`ConfiguredSound` + `@HeaderComments`/`@ConfigPath`）+ `Main extends EasyPlugin` | 只读配置 + 数据文件 | 无 PepperLib（自有 Carm 依赖 + shadow relocate `cc.carm.lib`） | **P3 边界待裁决（OQ-5）**：第三方案件技术栈，交换成本高 |

---

## 3. 分项目配置文件详情

### 3.1 PepperLib（前置库本尊，P0/P1 已完成）

- `ltd.pepper.lib.yaml`：`YamlMap.parse`（SafeConstructor、禁 timestamp、重复键报错、空文档→空 Map、
  多文档拒绝、BOM 剥离）、`YamlParseException(line,column,problem)`、`YamlMerge`（文本模板合并，
  putIfAbsent + 注释保留，金样测试逐字断言）。
- `ltd.pepper.lib.config`：`ConfigFile`（copyDefaultIfMissing / readUtf8 / writeAtomic）、
  `Values`（bool/int/long/double/string/stringList/enumOf 静默回落）、`IssueCollector`/`ConfigIssue`/
  `IssueLevel`、`UnknownKeys`、`ConfigVersions`（configVersion 链式迁移）、`UpgradePatch`（升级补键）。
- 纯 JDK 零 Bukkit；snakeyaml 2.6 显式进 `libs.versions.toml`，运行期由服务端捆绑提供（不打包不传递）。
- 运行形态：**前置插件模式**（`PepperLib.jar` + 消费方 compileOnly + `load: BEFORE required`，
  经 `ServicesManager` 校验 `PepperLibRuntime`）/ **shade 模式**（第三方 relocate）。

### 3.2 PepperTreeCut（P2 已迁移 = 迁移样板的"重语义先行"）

- 结构：`config.yml` + `profiles/*.yml` 多文件（extends 继承、字典序合并、同名覆盖、tag#展开、土壤家族）。
- 领域层驻留插件：`ConfigLoader`（纯字符串装载）+ `ConfigHolder`（volatile 原子替换，reload 遇 fatal
  保留旧配置）+ `ConfigValidator`（未知键/范围校验）+ `ConfigMigrator`（v1→v2）+ `ValidationReport`。
- 与库的分工：取值/解析/迁移链/issue 容器来自 PepperLib；合并顺序、extends、tag 展开等领域语义留插件。
- `configVersion: 2` 根键；`profiles/10-vanilla.yml`、`20-custom.yml` 为内置 profile 模板。

### 3.3 PepperClaim（P3 重点，多文件 + 写回语义改造）

- 9 个默认文件皆在 `src/main/resources/defaults/`（首跑复制到 dataFolder 根）。
- 架构接缝现成：`ConfigSource`（SPI，javadoc 预留"未来可换 okaeri"）+ `BukkitYamlSource`（现实现）；
  业务代码只见 `Settings` record（8 个 domain settings record），永不见 YAML 类型。
- 问题点：`loadWithDefaults()` **每次加载** 都 `disk.save()`（`copyDefaults` + 对象往返 → 全部注释丢失），
  升级合键靠 diffLog 通告。迁移后应按 §8.3 改为**升级补键/命令触发** + 注释保留写回（行为变更需通告）。

### 3.4 PepperUnion（P3 中档，内部实现换）

- 单文件 `config.yml`，只读；`PluginSettings.from(FileConfiguration)` 手工读 + 防御归一
  （金额取整到分、正则编译回落、等级曲线过滤、时间格式校验等）→ 域 record，旧 getter 全委托保留。
- 附带：`StorageSettings`（读 FileConfiguration）、`GuildConfigType`（联盟配置键的值类型描述符，
  其中 ITEM 类型用 `YamlConfiguration` 做物品序列化——属**数据文件/框架语义**，迁移时注意留界）。
- i18n：`lang/zh_CN.yml`、`en_US.yml`（已走 lib i18n？）；`trchat/guild-channel.yml` 为 TrChat 频道模板。

### 3.5 GlowingSquad（P3 试点已完成 = 混合形态样板）

- `config.yml` 仅 1 键；`@Configuration SquadConfig`（字段默认值 + `@Comment`）+ ConfigLib 只读 `load`；
  `saveDefaultConfig()` 材质化带注释默认文件；程序化读 `raw.maxDistance()`。
- 数据文件 `data.yml`（`GlowToggleStore`，Bukkit YamlConfiguration）→ 按 §2 非目标**排除在迁移外**。
- 注意：`config.yml` 存在时 ConfigLib 空文件会抛异常，需按设计文档 §16 材质化兜底。

### 3.6 PepperMinecart（P3 简单档）

- 单文件 `config.yml`（约 22 键，无版本号），只读，`/pm reload` 全量重载。
- `PluginConfig` 自带健壮读取助手（intInRange 钳制、NaN 防护、checkedList 告警、枚举回落），
  迁移时可把这些助手语义沉淀进库的 `Values`（与 treecut 同形扩展）。

### 3.7 PepperTrashBin（P3 中档，换源）

- Jackson YAML Bean 体系成熟（snake_case 自动映射、FAIL_ON_UNKNOWN_PROPERTIES=false、null 跳过、
  范围归一、原子写、保留旧配置）。迁移到家族引擎 = 替换解析层，保留 Bean/归一/提交语义；
  杰克逊当前不依赖服务端捆绑 snakeyaml（自带 jackson-dataformat-yaml）——迁移可减少一个依赖面。

### 3.8 PepperPvpArena（P3 边界判定 OQ-5）

- **目前没有 config.yml**；`ltd.pepper.pvparena.config` 是占位包（"当前框架阶段只声明包边界，不实现配置加载"）。
- `ArenaMapLoader` 读写 `maps/<id>.yml`（地图/模板/出生点/状态机）→ **数据文件，排除**。
- 主配置可直接按新体系从零建立（无需迁移兼容负担）。

### 3.9 UserPrefix（P3 边界判定 OQ-5，外部技术栈）

- 配置走 Carm MineConfiguration（`cc.carm.lib.configuration` + `cc.carm.lib.mineconfiguration-bukkit`，
  pom 声明 + shadow relocate）；`PluginConfig implements Configuration` 静态注解字段。
- `prefixes/*.yml`（`PrefixManager` 用 Bukkit YamlConfiguration 读写）→ **数据文件，排除**。
- 该插件源自 cc.carm 生态（EasyPlugin 基类），与 Pepper 家族技术栈不同源，交换成本最高 → 边界裁决后再定。

---

## 4. PepperBot 机器人插件群（独立模块，不在当前蓝图）

每个插件自带 config 装载器，形态各异（均为 Bukkit `getConfig()`/自研解析）：

| 插件 | 配置文件 | 装载类 |
|---|---|---|
| PepperBotCore（核心） | `config.yml`（深嵌套：bots/<id>/connection/ws|http|reverse-ws|webhook…） | `config/ConfigLoader` + `ConfigException` |
| PepperBotBindManager | `config.yml` | `config/BindManagerConfig` |
| PepperBotChatSync | `config.yml` | `config/ChatSyncConfig` |
| PepperBotCommandDispatcher | `config.yml` | `CommandDispatcherConfig` |
| PepperBotCustomMessage | `config.yml` + 规则/模板 | `GlobalConfig`/`RuleConfig`/`TemplateConfig` + `RuleLoadException` |
| PepperBotPlugin | `config.yml` | `PepperConfig` |

> 若前置库后续覆盖这批插件，同样可换家族引擎；但**配置文件 schema 与装载语义差异大，建议单独立项**。

---

## 5. 与「配置文件前置库」蓝图的关系（落地进度）

设计文档 `PepperLib/docs/yaml-config-system-design.md` 的分期与当前状态：

| 期 | 内容 | 状态 |
|---|---|---|
| P0（lib 0.10.0） | `YamlMap` + `YamlParseException` + 语义矩阵测试 + snakeyaml 显式化 + i18n 收敛 | ✅ 已完成（CHANGELOG 0.10.0） |
| P1（lib 0.11.0） | `YamlMerge`（spike 裁定文本合并）+ `config` 工具链全套 + 金样测试 | ✅ 已完成（CHANGELOG 0.11.0） |
| P2 | PepperTreeCut 迁移（Yaml.java 退役、助手换 Values、基线 387 全绿） | ✅ 已完成（0 处 Bukkit config） |
| P3 | 逐家迁移：~~GlowingSquad(ConfigLib 试点)~~ → Minecart → TrashBin → Union → Claim → UserPrefix/PvpArena | 🔄 试点已完成并**反迁自研**（0.12.0）；其余待迁移 |
| 配套 | 原 §16：绑定层采用 Exlll ConfigLib 的自研退役计划，由 **§16.1 裁决反转**（0.12.0 自研注解绑定 + 注释操作 + ConfigFileStore）替代 | ✅ 反转已落地（GlowingSquad 反迁验证） |

**前置库完整形态（服务器侧）**：
1. `PepperLib.jar`（引擎+工具链+自研绑定/注释/运行时存储，未 relocate 的 `ltd.pepper.lib.*` 单一实例）；
2. 各消费插件 `paper-plugin.yml` 声明 `PepperLib`（及自身领域前置）为 `load: BEFORE, required: true`。

## 6. 关键结论（供后续制作前置库参考）

1. **引擎与工具链已就绪**：新插件接入零成本；重点是把 P3 剩余插件迁移到位，每家验收 =
   默认值等价测试 + 零 `org.bukkit.configuration` import + 实服冒烟。
2. **迁移顺序（按设计文档 P3）**：GlowingSquad（✅）→ PepperMinecart（简单）→ PepperTrashBin（换源）→
   PepperUnion（内部实现换、域 record 不动）→ PepperClaim（兑现 ConfigSource SPI 预留 + 写回语义改造，
   行为变更需通告）→ UserPrefix/PepperPvpArena（边界 OQ-5 裁决）。
3. **写回语义变革点**：Claim 目前"每次加载补默认并落盘（毁注释）"，迁移后改"升级补键/命令触发 +
   注释保留写回"——这是唯一涉及行为变更的迁移。
4. **数据文件一律排除**：GlowToggleStore data.yml、Union 联盟配置值/StorageSettings、PvpArena maps/*.yml、
   UserPrefix prefixes/* 与 i18n、PersistentStore——不纳入配置文件前置库（走 persist/各自 codec）。
5. **PepperBot 群**是独立候选，scheme 差异大，建议单独立项。
