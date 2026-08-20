package io.pepper.lib.i18n;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * 统一 i18n 语言包（内部设计文档 i18n-unified-design）。
 *
 * <p>两阶段渲染：{@link #reload()} 时每个 locale 的每条消息预解析一次并缓存为
 * {@link Component}；渲染阶段仅做占位符替换。回退链：玩家 locale L → 默认 locale →
 * 回退 locale → 键名（跳过重复）。</p>
 *
 * <p>加载语义：bundled 资源为底，存在的磁盘文件逐键覆盖胜出；磁盘文件缺失时直接使用 bundled，
 * 不自动写入磁盘。磁盘 YAML 语法错误或单个坏模板降级回退，绝不中断 reload。全部映射在完整构建后一次性
 * 换入（volatile），并发的 {@code format} 不会观察到半加载状态。</p>
 */
public final class LanguageBundle {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Path dataFolder;
    private final Function<String, InputStream> resourceLoader;
    private final List<String> locales;
    private final String defaultLocale;
    private final String fallbackLocale;

    private volatile LocaleResolver localeResolver = (player, def) -> def;
    private volatile PlaceholderResolver placeholderResolver;
    private volatile Logger logger;

    private volatile Map<String, Map<String, String>> rawMessages = Map.of();
    private volatile Map<String, Map<String, Component>> templates = Map.of();

    /**
     * @param dataFolder 插件数据目录（磁盘语言文件位于 {@code lang/} 下）
     * @param resourceLoader bundled 资源加载器（按资源路径返回输入流，如
     *     {@code plugin.getClass().getResourceAsStream(path)}）
     * @param locales 语言文件名前缀列表（如 {@code ["zh_CN", "en_US"]}）
     * @param defaultLocale 默认语言（必须 ∈ locales）
     * @param fallbackLocale 回退语言（必须 ∈ locales）
     */
    public LanguageBundle(
            final Path dataFolder,
            final Function<String, InputStream> resourceLoader,
            final List<String> locales,
            final String defaultLocale,
            final String fallbackLocale) {
        if (locales.isEmpty()) {
            throw new IllegalArgumentException("locales must not be empty");
        }
        if (!locales.contains(defaultLocale)) {
            throw new IllegalArgumentException("defaultLocale must be in locales: " + defaultLocale);
        }
        if (!locales.contains(fallbackLocale)) {
            throw new IllegalArgumentException("fallbackLocale must be in locales: " + fallbackLocale);
        }
        this.dataFolder = dataFolder;
        this.resourceLoader = resourceLoader;
        this.locales = List.copyOf(locales);
        this.defaultLocale = defaultLocale;
        this.fallbackLocale = fallbackLocale;
    }

    /** 注入插件 {@link Logger}（缺省回退到类 Logger）。 */
    public void setLogger(final Logger logger) {
        this.logger = logger;
    }

    private void warn(final String message) {
        final Logger logger = this.logger;
        if (logger != null) {
            logger.warning(message);
        } else {
            Logger.getLogger(LanguageBundle.class.getName()).warning(message);
        }
    }

    /**
     * （重新）加载语言文件（内置默认值 + 可选的磁盘覆盖配置）并将每个模板预解析为缓存的
     * {@link Component}。新的映射在完全构建好之后才被替换进来。
     *
     * <p>线程约束：本方法同步（{@code synchronized}），并发的 {@code reload} 不会交错；
     * 渲染方法（{@code format*} / {@code raw*}）随时可并发调用——volatile 快照保证
     * 读者不会观察到半加载态。</p>
     */
    public synchronized void reload() {
        final Map<String, Map<String, String>> raws = new HashMap<>();
        for (final String locale : this.locales) {
            raws.put(locale, this.loadLocale(locale));
        }
        final Map<String, Map<String, Component>> parsed = new HashMap<>();
        for (final String locale : this.locales) {
            parsed.put(locale, this.parseAll(locale, raws));
        }
        this.rawMessages = Map.copyOf(raws);
        this.templates = Map.copyOf(parsed);
    }

    /**
     * 加载单个 locale：bundled 资源为底 + 已存在的磁盘文件逐键覆盖；磁盘缺失时不写入文件。
     */
    private Map<String, String> loadLocale(final String locale) {
        final String path = "lang/" + locale + ".yml";
        final File file = this.dataFolder.resolve(path).toFile();
        final Map<String, String> merged = new LinkedHashMap<>();
        try (InputStream in = this.resourceLoader.apply(path)) {
            if (in != null) {
                final Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map) {
                    this.collect(map, "", merged);
                }
            }
        } catch (final Exception e) {
            this.warn("failed to load bundled language " + path + ": " + e);
        }
        if (file.exists()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                final Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map) {
                    this.collect(map, "", merged);
                }
            } catch (final Exception e) {
                // 用户改坏的语言文件（如 YAML 语法错误）不得中断整个重载。
                this.warn("failed to parse language file " + file + ": " + e);
            }
        }
        return Map.copyOf(merged);
    }

    /** 展平 SnakeYAML 顶层/嵌套映射；点号键保持字面量，避免路径覆盖。 */
    private void collect(final Map<?, ?> source, final String prefix, final Map<String, String> out) {
        for (final Map.Entry<?, ?> entry : source.entrySet()) {
            final String key = String.valueOf(entry.getKey());
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                this.collect(nested, path, out);
            } else if (value != null) {
                out.put(path, String.valueOf(value));
            }
        }
    }

    private Map<String, Component> parseAll(final String locale, final Map<String, Map<String, String>> raws) {
        final Map<String, Component> result = new HashMap<>();
        for (final String key : this.allKeys(raws)) {
            result.put(key, this.parse(this.resolve(key, locale, raws)));
        }
        return Map.copyOf(result);
    }

    private Set<String> allKeys(final Map<String, Map<String, String>> raws) {
        final Set<String> keys = new LinkedHashSet<>();
        for (final Map<String, String> raw : raws.values()) {
            keys.addAll(raw.keySet());
        }
        return keys;
    }

    /** 解析单个模板；MiniMessage 标记格式错误时降级为纯文本组件。 */
    private static Component parse(final String raw) {
        try {
            return MINI_MESSAGE.deserialize(raw);
        } catch (final RuntimeException e) {
            return Component.text(raw);
        }
    }

    /**
     * 回退链：locale L → 默认 → 回退 → 键名（跳过重复）。
     */
    private String resolve(final String key, final String locale, final Map<String, Map<String, String>> raws) {
        final String value = get(raws, locale, key);
        if (value != null) {
            return value;
        }
        if (!this.defaultLocale.equals(locale)) {
            final String def = get(raws, this.defaultLocale, key);
            if (def != null) {
                return def;
            }
        }
        if (!this.fallbackLocale.equals(locale) && !this.fallbackLocale.equals(this.defaultLocale)) {
            final String fb = get(raws, this.fallbackLocale, key);
            if (fb != null) {
                return fb;
            }
        }
        return key;
    }

    private static String get(final Map<String, Map<String, String>> raws, final String locale, final String key) {
        final Map<String, String> raw = raws.get(locale);
        return raw == null ? null : raw.get(key);
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────

    /** 按默认 locale 格式化键。 */
    public Component format(final String key, final Map<String, TextValue> placeholders) {
        return this.formatForLocale(this.defaultLocale, key, placeholders);
    }

    /** {@link #format(String, Map)} 的扁平键值对版本（值按 mini 处理）。 */
    public Component format(final String key, final String... kv) {
        return this.format(key, toValues(kv));
    }

    /**
     * 按玩家 locale 格式化键；设置了 {@link PlaceholderResolver} 时先经钩子解析模板
     * （PAPI 输出视为受信模板内容），否则走预解析缓存。
     */
    public Component formatForPlayer(final Player player, final String key, final Map<String, TextValue> placeholders) {
        if (this.placeholderResolver == null) {
            return this.formatForLocale(this.localeNameFor(player), key, placeholders);
        }
        return this.applyPlaceholders(this.parse(this.rawForPlayer(player, key)), placeholders);
    }

    /** 格式化已解析的原始模板（PAPI 等外部解析后调用）。 */
    public Component formatRaw(final String rawTemplate, final Map<String, TextValue> placeholders) {
        return this.applyPlaceholders(parse(rawTemplate), placeholders);
    }

    private Component formatForLocale(
            final String locale, final String key, final Map<String, TextValue> placeholders) {
        Component template = this.templates.getOrDefault(locale, Map.of()).get(key);
        if (template == null) {
            template = parse(this.resolve(key, locale, this.rawMessages));
        }
        return this.applyPlaceholders(template, placeholders);
    }

    /** 返回键的原始模板字符串（默认 → 回退 → 键名）。 */
    public String raw(final String key) {
        return this.resolve(key, this.defaultLocale, this.rawMessages);
    }

    /** 返回键的原始模板字符串（按玩家 locale，经 {@link PlaceholderResolver}）。 */
    public String rawForPlayer(final Player player, final String key) {
        String raw = this.resolve(key, this.localeNameFor(player), this.rawMessages);
        final PlaceholderResolver resolver = this.placeholderResolver;
        if (resolver != null) {
            raw = resolver.resolve(player, raw);
        }
        return raw;
    }

    /** 返回键的原始模板字符串（按指定语言环境，默认 → 回退 → 键名）。 */
    public String rawForLocale(final Locale locale, final String key) {
        return this.resolve(key, this.matchLocale(locale), this.rawMessages);
    }

    // ── locale 解析 ──────────────────────────────────────────────────────────

    /** 设置玩家 → 语言环境解析器（缺省恒返回默认 locale）。 */
    public void setLocaleResolver(final LocaleResolver resolver) {
        this.localeResolver = resolver;
    }

    /** 设置外部占位符解析器（PAPI 等；缺省无）。 */
    public void setPlaceholderResolver(final PlaceholderResolver resolver) {
        this.placeholderResolver = resolver;
    }

    private String localeNameFor(final @Nullable Player player) {
        final Locale locale = this.localeResolver.resolve(player, toLocale(this.defaultLocale));
        return this.matchLocale(locale);
    }

    /** Locale → 文件匹配：语言_地区 精确 → 仅语言（首个）→ 默认。 */
    private String matchLocale(final @Nullable Locale locale) {
        if (locale == null) {
            return this.defaultLocale;
        }
        final String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        final String country = locale.getCountry().toLowerCase(Locale.ROOT);
        String languageOnly = null;
        for (final String name : this.locales) {
            final String[] parts = name.split("_");
            if (parts[0].equalsIgnoreCase(language)) {
                if (parts.length > 1 && parts[1].equalsIgnoreCase(country)) {
                    return name;
                }
                if (languageOnly == null) {
                    languageOnly = name;
                }
            }
        }
        return languageOnly != null ? languageOnly : this.defaultLocale;
    }

    private static Locale toLocale(final String name) {
        final String[] parts = name.split("_");
        return parts.length > 1 ? Locale.of(parts[0], parts[1]) : Locale.of(parts[0]);
    }

    // ── 占位符替换与安全 ───────────────────────────────────────────────────────

    /**
     * 按迭代顺序逐键替换占位符；后替换的键可能匹配到先插入值中的 {@code %...%} 文本
     * （与两插件现状语义一致）；未知占位符保留原样。
     */
    private Component applyPlaceholders(final Component template, final Map<String, TextValue> placeholders) {
        Component result = template;
        if (placeholders == null || placeholders.isEmpty()) {
            return result;
        }
        for (final Map.Entry<String, TextValue> entry : placeholders.entrySet()) {
            final TextValue value = entry.getValue();
            final Component replacement;
            if (value == null || value.isLiteral()) {
                // 字面文本：不解析 MiniMessage，原样渲染（含 < > 等字符）。
                replacement = Component.text(value == null ? "" : value.value());
            } else {
                // 按受限 MiniMessage 解析：保留颜色/格式与 hover，剥离全部点击事件与
                // 插入文本，防止占位符值（公告、玩家名、公会描述）经解析后变成执行命令、
                // 钓鱼链接或聊天框注入。
                replacement = sanitizeUserContent(parseReplacement(value.value()));
            }
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + entry.getKey() + "%")
                    .replacement(replacement)
                    .build());
        }
        return result;
    }

    /** 解析占位符值；MiniMessage 标记格式错误时降级为纯文本。 */
    private static Component parseReplacement(final String raw) {
        try {
            return MINI_MESSAGE.deserialize(raw == null ? "" : raw);
        } catch (final RuntimeException e) {
            return Component.text(raw == null ? "" : raw);
        }
    }

    /**
     * 递归净化用户提供的占位符值（非 {@link TextValue#literal} 内容）。
     *
     * <p>保留：颜色、格式（粗体 / 斜体 / 下划线等）与 hover（悬浮文本）。剥离：全部点击
     * 事件（无论 action 类型）与插入文本（shift+点击），防止用户内容经解析后变成任意命令
     * 执行、钓鱼链接、聊天框预填或注入。</p>
     */
    private static Component sanitizeUserContent(final Component component) {
        Component result = component;
        // 剥离点击事件（任何 action）；Adventure 传 null 即清除。
        if (result.clickEvent() != null) {
            result = result.clickEvent(null);
        }
        // 剥离插入文本（shift+点击把内容塞进聊天框）。
        if (result.insertion() != null) {
            result = result.insertion(null);
        }
        final List<Component> children = result.children();
        if (children.isEmpty()) {
            return result;
        }
        final List<Component> sanitized = new ArrayList<>(children.size());
        boolean changed = false;
        for (final Component child : children) {
            final Component cleaned = sanitizeUserContent(child);
            changed |= cleaned != child;
            sanitized.add(cleaned);
        }
        return changed ? result.children(sanitized) : result;
    }

    private static Map<String, TextValue> toValues(final String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Key/value pairs must be even, got " + kv.length);
        }
        final Map<String, TextValue> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], TextValue.mini(kv[i + 1]));
        }
        return map;
    }

    /** 返回某 locale 展平后的原始键值快照（不可变）。 */
    public Map<String, String> rawMessages(final String locale) {
        return this.rawMessages.getOrDefault(locale, Map.of());
    }
}
