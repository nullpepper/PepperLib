# 统一 i18n 机制设计（`io.pepper.lib.i18n`）

> 目标：汲取 PepperClaim 与 PepperUnion 两套 i18n 方案的优点，在 PepperLib 中设计一套
> 统一机制；两个插件保留**调用点零改动**的薄壳，行为差异全部在本文档 §8 显式列出。
> 依据：`docs/adoption-decisions.md` 接入纪律（双消费者 → 机制进 lib，策略留插件）。

## 1. 设计原则（汲取映射）

| 来源 | 汲取点 | 落地 |
|---|---|---|
| Claim | 玩家级 locale（override 持久化 + 客户端 locale） | `LocaleResolver` 扩展点（§6） |
| Claim | 磁盘覆盖逐键合并（bundled 为底，磁盘胜出） | 加载管线（§5） |
| Claim | SnakeYAML 展平加载（点号键保持字面量，规避 Bukkit YamlConfiguration 路径覆盖） | 加载管线（§5） |
| Claim | 简单 String-map 渲染 API（小插件友好） | 壳层便利 API（§9） |
| Union | 两阶段渲染：模板预解析缓存（解析只发生一次） | 渲染管线（§4） |
| Union | `TextValue` 类型化占位符（literal / mini 类型层面区分） | `io.pepper.lib.i18n.TextValue`（§3） |
| Union | `sanitizeUserContent` 防注入（剥离点击事件/插入文本） | 安全语义（§4.2） |
| Union | 回退链 default → fallback → key + 坏模板降级纯文本 | 渲染管线（§4） |
| Union | 原子 reload（新映射完整构建后一次性换入） | 加载管线（§5） |
| Union | PAPI 软依赖解析机制整体移入 lib（守卫 + 惰性类加载 + 异常兜底） | `PapiPlaceholderResolver`（§7） |
| 新 | 每-locale 模板缓存（Union 只有单缓存；玩家级 locale 需要按 locale 缓存） | 渲染管线（§4） |

## 2. 语言文件格式规范（唯一真源）

文件：`lang/<locale>.yml`，YAML 编码 UTF-8，键值对，值为 MiniMessage 文本：

```yaml
claim.general.failed: "<red>失败: %reason%</red>"
union.info.name: "<gold>公会名:</gold> %name%"
```

- **键**：支持点号键（扁平）或嵌套映射（展平后以 `.` 连接）；含字面点号的键在 YAML 中加引号
  `"a.b": ...`，加载器用 SnakeYAML 展平，**字面点号不被拆开**（Claim 方案）。
- **占位符**：`%name%` 语法；值中出现的 `%` **无需转义**（替换发生在已解析组件上，不重新解析）。
- **未知占位符保留原样**：模板中未被替换的 `%...%` 保持字面文本输出（供 PAPI 后处理，§7）。
- **未知键**：回退链（§6）→ 最终回退为键名本身。
- **坏模板**（MiniMessage 语法错误）：降级为纯文本组件并警告一次（不中断 reload）。

两插件现有语言文件与本规范 100% 兼容，文件可原样迁移。

## 3. 核心 API

```java
package io.pepper.lib.i18n;

/** 类型化占位符值（自 Union TextValue 原样移植）。 */
public final class TextValue {
    public static TextValue literal(String value); // 纯文本，不解析 MiniMessage（用户内容）
    public static TextValue mini(String value);    // 按 MiniMessage 解析（受信格式化内容）
    public String value();
    public boolean isLiteral();
}

/** 玩家 → 语言环境 解析（策略留插件）。 */
@FunctionalInterface
public interface LocaleResolver {
    Locale resolve(@Nullable Player player, Locale defaultLocale);
}

/** 外部占位符解析钩子（模板解析前字符串级执行）。 */
@FunctionalInterface
public interface PlaceholderResolver {
    String resolve(@Nullable Player player, String text);
}

/** PAPI 软依赖内置实现（见 §7）；是否启用由插件决定（opt-in）。 */
public final class PapiPlaceholderResolver implements PlaceholderResolver {
    public static final PapiPlaceholderResolver INSTANCE = new PapiPlaceholderResolver();
    private PapiPlaceholderResolver() {}
}

public final class LanguageBundle {

    /** locales：语言文件名前缀（如 ["zh_CN", "en_US"]）；default/fallback 必须 ∈ locales。 */
    public LanguageBundle(Path dataFolder, Function<String, InputStream> resourceLoader,
            List<String> locales, String defaultLocale, String fallbackLocale);

    public void setLogger(Logger logger);
    public void reload(); // 原子换入：加载 → 全量预解析 → swap

    // ── 渲染
    public Component format(String key, Map<String, TextValue> placeholders);   // 默认 locale
    public Component format(String key, String... kv);                          // 值按 mini 处理
    public Component formatForPlayer(Player player, String key, Map<String, TextValue> placeholders);
    public Component formatRaw(String rawTemplate, Map<String, TextValue> placeholders); // 已解析模板（PAPI 后）
    public String raw(String key);        // default → fallback → key
    public String rawForPlayer(Player player, String key);                      // 经 PlaceholderResolver
    public String rawForLocale(Locale locale, String key);                      // 按指定语言环境

    // ── 扩展点（缺省行为见 §6/§7）
    public void setLocaleResolver(LocaleResolver resolver);
    public void setPlaceholderResolver(PlaceholderResolver resolver);

    // ── 调试/兼容
    public Map<String, String> rawMessages(String locale); // 展平后的原始键值快照
}
```

## 4. 渲染管线

### 4.1 两阶段（Union 方案，泛化到每-locale）

1. **加载阶段**：每个 locale 的每条消息解析一次，缓存为 `Component` 模板
   （`Map<locale, Map<key, Component>>`，reload 时全量重建，volatile 原子换入）。
   — Claim 的玩家级 locale 要求缓存按 locale 分桶；Union 单缓存是其特例（仅默认 locale）。
2. **渲染阶段**：取模板 → 对每个占位符 `%key%` 执行 `replaceText(matchLiteral)` 替换。

替换语义（显式规定，与两插件现状一致）：
- 替换作用于模板组件树全部文本节点；同一占位符多处出现全部替换；
- **按传入 Map 的迭代顺序逐键替换，后替换的键可能匹配到先插入值中的 `%...%` 文本**
  （两插件现状均如此，保持兼容；调用方用 `literal()` 规避用户内容碰撞）；
- 未知占位符不匹配任何键时保留原样。

### 4.2 占位符值处理（安全语义，Union 方案）

| TextValue | 处理 | 保留 | 剥离 |
|---|---|---|---|
| `literal(v)` | 原样文本组件（不解析） | — | — |
| `mini(v)` | MiniMessage 解析（坏语法降级纯文本） | 颜色/格式/hover | **点击事件（全部 action）+ 插入文本**（递归全树） |

`formatForPlayer` 的 PAPI 解析输出视为**受信模板内容**（解析后不净化）；内部占位符值仍按上表处理。

## 5. 加载与更新语义

每 locale：
1. bundled 资源 + 可选磁盘覆盖**逐键合并**：bundled 为底，磁盘覆盖胜出
   （两插件现状语义一致：Union `setDefaults` / Claim 顺序 put）；
2. **不自动复制 bundled → 磁盘**（决策）：Claim 旧版首启复制会在插件更新后让旧磁盘
   快照**遮蔽** bundled 新文本（全键复制而非逐键补缺）；Union 现状不复制。管理员可手动
   创建 `lang/<locale>.yml` 覆盖——FallbackLanguage 降级路径因此保持纯 bundled 契约；
3. 磁盘 YAML 语法错误 → 警告 + 仅用 bundled；
4. 单个坏模板 → 降级纯文本（§2），不中断 reload；
5. 全部构建完成后一次性 swap（原子性）。

reload 语义：磁盘改动在 reload 后才可见（缓存快照）；插件更新后 bundled 新文本对未覆盖键生效。

## 6. locale 解析与语言文件选择

- 解析链（每 locale 消息回退）：**玩家 locale L → 默认 locale → 回退 locale → 键名**（跳过重复）。
  特例核对：L=默认 时 = Union 现状（default → fallback → key）；Claim 现状（zh → en → key）不变。
- `LocaleResolver` 缺省实现：恒返回 defaultLocale。
- Claim 壳注入：override 持久化 map → 客户端 `player.locale()` → defaultLocale（现状语义不变）。
- Locale → 文件匹配：`语言_地区` 精确匹配 → 仅语言匹配（首个）→ 默认 locale。
  （`Locale.SIMPLIFIED_CHINESE` → `zh_CN`；`Locale.ENGLISH` → `en_US`。）

## 7. PAPI：解析机制入 lib（软依赖，opt-in）

**可行性结论：可行，且推荐。** PAPI 解析是通用软依赖机制（非任一插件专属策略），
符合「机制进 lib」纪律；Union 中 `PapiSupport` 仅 `formatForPlayer` 一个使用点，可整类删除。

- `PapiPlaceholderResolver implements PlaceholderResolver`：完整移植 Union PapiSupport 守卫模式
  （lib 以 `compileOnly("me.clip:placeholderapi:2.11.6")` 软依赖，与 Union 同坐标、同
  extendedclip 仓库——Union CI 已在用，可达性已验证）：
  1. player 为空 / 文本不含 `%` → 原样返回（不触碰 PAPI 类）；
  2. `Bukkit.getPluginManager().getPlugin("PlaceholderAPI")` 未安装或未启用 → 原样返回
     （守卫先行，PAPI 类永不加载——软依赖运行时安全，生产已验证模式）；
  3. 解析调用包 `RuntimeException` 兜底，绝不中断原消息渲染。
- **启用是插件策略（opt-in）**：`bundle.setPlaceholderResolver(PapiPlaceholderResolver.INSTANCE)`
  一行。Union 壳启用（现状语义不变）；Claim 壳**不启用**（现状无 PAPI，避免静默行为变化）。
- `formatForPlayer`：`rawForPlayer`（经钩子，未设置则跳过）→ 解析为受信模板 → 内部占位符替换。
- `PlaceholderResolver` 接口保留：非 PAPI 的自定义占位符系统仍可注入。
- 代价：lib 首个第三方可选编译依赖 + 首个外部仓库（paper/central 之外）；compileOnly 不传递，
  消费方插件与发布产物无感；单测对「PAPI 已注册扩展」正路径覆盖有限（与 Union 现状相同，
  真实路径由 Union 集成运行覆盖，§11 列出覆盖边界）。

## 8. 行为差异清单（迁移影响，全部显式化）

| # | 差异 | 影响 | 处置 |
|---|---|---|---|
| 1 | Claim：发送时整串解析 → 模板缓存 + 值级 mini 解析 | 可见输出不变（除净化）；磁盘改动需 reload 才生效（Union 现状已如此） | 接受（文档化） |
| 2 | Claim：占位符值获得净化（剥离点击/插入） | 行为收紧；现无调用点携带事件 | 接受（安全收益） |
| 3 | Claim：递归翻译**保留为壳层语义**（lib 不提供——与 Union 严格语义一致） | **help 系统真实依赖**（PlayerMiscCommands 传 usage/help 键值，LoadsAllKeys 测试固化契约）→ 壳层 raw() 与 render() 均预翻译键值 | 壳内实现（depth<3 同旧逻辑，行为零变化） |
| 4 | Union：`formatText` 移除 | **零调用点**（已核实） | 移除，typed 入口走 lib `format` |
| 5 | Union：String-map 值统一经 mini 净化 | 与现状非字面路径一致（本就净化） | 无 |
| 6 | Union：`literal()` 标记字符（`\uE000`）保留为壳内兼容层 | 35 处调用点零改动 | 壳内 String→TextValue 转换识别标记 |
| 7 | Claim 4 参 `raw(key, locale, placeholders, depth)` 保留签名，depth 语义删除 | GUI/引导页 3 处调用点零改动 | 壳内实现不含递归 |
| 8 | Union：`papi/PapiSupport.java` 删除，改一行 resolver 装配 | 净删 33 行；formatForPlayer 语义不变（PAPI 机制等价移入 lib） | 接受 |
| 9 | Claim：不启用 PAPI 解析（opt-in） | 现状无 PAPI，行为零变化 | 接受 |

## 9. 插件壳适配（调用点零改动）

**Claim 壳**（158 → ~85 行）：保留 `render(key, player, Map<String,String>)`、
`raw(key, locale, placeholders, depth)`、`effectiveLocale(player)`、
`setLocaleOverride / loadLocaleOverrides / clearLocaleOverride`、`rawMessages()`；
**递归翻译保留**（壳层语义：值命中语言键 → 经 `rawForLocale` 递归翻译，depth<3，raw 与
render 双路径一致）；String 值 → `TextValue.mini`；`LocaleResolver` 由 override map +
客户端 locale 实现；`rawMessages()` 重组为 `locale.key` 形态（旧测试契约）。

**Union 壳**（343 → ~80 行）：保留 `format(key, Map<String,String>)`、`format(key, String...)`、
`formatForPlayer(Player, key, Map)`、`formatForPlayer(Player, key, String...)`、
`literal(String)`（标记字符兼容）、`raw(key)`；String→TextValue 转换识别 `\uE000` 前缀；
装配 `setPlaceholderResolver(PapiPlaceholderResolver.INSTANCE)`（删除 `papi/PapiSupport.java`）；
移除 `formatText`（零调用点）。

## 10. 非目标

- LanguageKey 常量类不统一（两插件键域不同，各自私有）。
- 文件格式不扩展条件/复数语法（复数场景由调用方按计数选键）。
- 不做远程语言包拉取、热更新监听。
- lib 仅 `compileOnly` 软依赖 PAPI（不打包、不传递、不强制消费方）；PAPI 是否启用是插件策略（opt-in）。
- 玩家 override 的持久化存储留在插件壳（策略不进 lib）。

## 11. 测试计划（TDD，lib ~17 个 + 两壳回归）

lib `LanguageBundleTest`（红 → 绿）：
1. 无磁盘文件：bundled 全量加载，**不写磁盘**
2. 磁盘逐键覆盖胜出；bundled 补缺
3. 磁盘 YAML 坏语法 → 警告 + 用 bundled
4. 坏模板 → 纯文本降级
5. 回退链 L → default → fallback → key（含重复跳过）
6. locale → 文件匹配（精确 / 仅语言 / 默认）
7. 多占位符全量替换；空 map 返回模板原样
8. 未知占位符保留原样
9. `format(key, kv...)` 便利形态
10. mini：解析标签；递归剥离点击事件 + 插入文本（含嵌套子组件）
11. literal：`<>` 与 `%...%` 原样输出
12. 迭代顺序重扫描语义（先插值含 `%后键%` 文本会被后键替换）
13. formatForPlayer：钩子先于解析执行；钩子输出受信；无钩子 = format
14. 每-locale 模板：zh/en 玩家取各自模板
15. reload 后新磁盘文本可见（原子换入冒烟）
16. LocaleResolver 收到 player 与 defaultLocale
17. PapiPlaceholderResolver：未安装 PAPI 插件 → 原样；player 为空 → 原样；文本无 `%` → 原样；
    PAPI jar 在场但插件未启用（testImplementation 引入 PAPI 2.11.6，与 Union 同版本）→ 原样
    ——「已注册扩展」正路径依赖真实 PAPI 运行时，由 Union 集成运行覆盖（与 Union 现状相同的覆盖边界）
18. `rawForLocale`：按指定语言环境解析回退链（en → 默认 → 回退 → 键名）

壳回归：Claim 现有 i18n 测试全绿（LoadsAllKeys 固化递归与合并契约）+ 新增「render 路径键值重译」
用例；Union `LanguageManagerTest` 全绿（含既有标记字符兼容用例 literalValuesAreNotParsedAsMiniMessage）。

## 12. 工作量与风险

| 项 | 工作量 | 风险 |
|---|---|---|
| lib `io.pepper.lib.i18n`（~380 行，含 PapiPlaceholderResolver ~40 行）+ 测试 | 0.5–1 天 | 低（纯新代码；PAPI 守卫模式已在 Union 生产验证） |
| Claim 壳瘦身 + 回归 | 0.5 天 | 低（9 处差异全部显式化，§8） |
| Union 壳瘦身 + 回归 | 0.5 天 | 低（35 处 literal 调用点零改动） |

风险点：① 壳转换层（String ↔ TextValue + 标记字符）是唯一新增兼容逻辑，用 §11 回归用例钉死；
② Claim 净化收紧若有未预见的调用点，运行时消息可见差异——§8 #2 已列，实施时用全键快照测试兜底。
