package io.pepper.lib.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * {@link LanguageBundle} 行为测试（设计文档 docs/i18n-unified-design.md §11）：
 * 加载/合并、回退链、locale 匹配、渲染/安全语义、reload、PAPI 钩子。
 */
class LanguageBundleTest {

    private static final List<String> ZH_EN = List.of("zh_CN", "en_US");
    private static final List<String> ZH_EN_DE = List.of("zh_CN", "en_US", "de_DE");

    private static InputStream resource(final String path) {
        return LanguageBundleTest.class.getClassLoader().getResourceAsStream(path);
    }

    private static LanguageBundle zhEn(final Path folder) {
        return new LanguageBundle(folder, LanguageBundleTest::resource, ZH_EN, "zh_CN", "en_US");
    }

    private static LanguageBundle zhEnDe(final Path folder) {
        return new LanguageBundle(folder, LanguageBundleTest::resource, ZH_EN_DE, "zh_CN", "en_US");
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Player player(final Locale locale) {
        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.locale()).thenReturn(locale);
        return player;
    }

    private static Map<String, TextValue> kv(final String key, final String value) {
        return Map.of(key, TextValue.mini(value));
    }

    private static boolean anyClick(final Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (final Component child : component.children()) {
            if (anyClick(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyInsertion(final Component component) {
        if (component.insertion() != null) {
            return true;
        }
        for (final Component child : component.children()) {
            if (anyInsertion(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyColor(final Component component) {
        if (component.color() != null) {
            return true;
        }
        for (final Component child : component.children()) {
            if (anyColor(child)) {
                return true;
            }
        }
        return false;
    }

    // ── 加载与合并 ────────────────────────────────────────────────────────────

    @Test
    void loadsBundledWithoutWritingDiskFiles(@TempDir final Path folder) throws Exception {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        // 设计 §5：不自动复制 bundled → 磁盘（避免插件更新后旧磁盘快照遮蔽新文本）。
        assertFalse(Files.exists(folder.resolve("lang/zh_CN.yml")));
        assertFalse(Files.exists(folder.resolve("lang/en_US.yml")));
        assertEquals("你好，Bob！", plain(bundle.format("greeting", "name", "Bob")));
    }

    @Test
    void diskOverrideWinsAndBundledFills(@TempDir final Path folder) throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/zh_CN.yml"), "greeting: \"改了：%name%\"\n");
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("改了：Bob", plain(bundle.format("greeting", "name", "Bob")));
        assertEquals("进度: 50 / 50", plain(bundle.format("with.placeholder", "progress", "50")));
    }

    @Test
    void brokenDiskYamlFallsBackToBundled(@TempDir final Path folder) throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/zh_CN.yml"), "::: not yaml\n");
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload(); // 不得抛出
        assertEquals("你好，Bob！", plain(bundle.format("greeting", "name", "Bob")));
    }

    @Test
    void brokenTemplateDegradesToPlainText(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("<gradient:red>坏", plain(bundle.format("broken.template")));
    }

    // ── 回退链与 locale ───────────────────────────────────────────────────────

    @Test
    void fallbackChainLocaleToDefaultToFallbackToKey(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEnDe(folder);
        bundle.setLocaleResolver((player, def) -> player == null ? def : Locale.GERMANY);
        bundle.reload();
        final Player de = player(Locale.GERMANY);
        assertEquals("Hallo, Bob!", plain(bundle.formatForPlayer(de, "greeting", kv("name", "Bob"))));
        assertEquals("English only", plain(bundle.formatForPlayer(de, "only.en", Map.of())));
        assertEquals("只在中文", plain(bundle.formatForPlayer(de, "only.zh", Map.of())));
        assertEquals("missing.key", plain(bundle.formatForPlayer(de, "missing.key", Map.of())));
        assertEquals("English only", plain(bundle.format("only.en")));
        assertEquals("missing.key", plain(bundle.format("missing.key")));
    }

    @Test
    void localeFileMatching(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEnDe(folder);
        bundle.setLocaleResolver((player, def) -> player.locale());
        bundle.reload();
        assertEquals(
                "Hello, Bob!",
                plain(bundle.formatForPlayer(player(Locale.of("en", "US")), "greeting", kv("name", "Bob"))));
        assertEquals(
                "Hello, Bob!", plain(bundle.formatForPlayer(player(Locale.ENGLISH), "greeting", kv("name", "Bob"))));
        assertEquals("你好，Bob！", plain(bundle.formatForPlayer(player(Locale.FRANCE), "greeting", kv("name", "Bob"))));
        assertEquals(
                "Hallo, Bob!", plain(bundle.formatForPlayer(player(Locale.GERMANY), "greeting", kv("name", "Bob"))));
    }

    @Test
    void defaultResolverUsesDefaultLocale(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("你好，Bob！", plain(bundle.formatForPlayer(null, "greeting", kv("name", "Bob"))));
        assertEquals("你好，Bob！", plain(bundle.formatForPlayer(player(Locale.ENGLISH), "greeting", kv("name", "Bob"))));
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    @Test
    void formatSubstitutesAllOccurrencesAndEmptyMap(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("进度: 50 / 50", plain(bundle.format("with.placeholder", "progress", "50")));
        assertEquals("你好，%name%！", plain(bundle.format("greeting")));
        assertEquals("你好，%name%！", plain(bundle.format("greeting", Map.of())));
    }

    @Test
    void unknownPlaceholderPreserved(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("你好，%name%！", plain(bundle.format("greeting", "other", "X")));
    }

    @Test
    void literalValueRenderedVerbatim(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        final Component result = bundle.format("payload.msg", Map.of("payload", TextValue.literal("<red>evil</red>")));
        assertEquals("内容: <red>evil</red>", plain(result));
        assertFalse(anyColor(result));
    }

    @Test
    void miniValueParsedAndSanitized(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        final Component result = bundle.format(
                "payload.msg",
                Map.of("payload", TextValue.mini("<click:run_command:'/say hi'><bold>x</bold></click>")));
        assertEquals("内容: x", plain(result));
        assertFalse(anyClick(result));
        assertTrue(MiniMessage.miniMessage().serialize(result).contains("<bold>"));
    }

    @Test
    void miniValueWithInsertionStripped(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        // MiniMessage 的插入标签是 <insert:...>；解析后文本节点携带 insertion 样式。
        final Component result =
                bundle.format("payload.msg", Map.of("payload", TextValue.mini("<insert:'evil'>x</insert>")));
        assertEquals("内容: x", plain(result));
        assertFalse(anyInsertion(result));
    }

    @Test
    void rescanOrderingSemantics(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        final Map<String, TextValue> map = new LinkedHashMap<>();
        map.put("a", TextValue.mini("%b%"));
        map.put("b", TextValue.mini("X"));
        assertEquals("A: X B: X", plain(bundle.format("rescan.msg", map)));
    }

    // ── formatForPlayer 与钩子 ─────────────────────────────────────────────────

    @Test
    void formatForPlayerAppliesResolverBeforeParse(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.setPlaceholderResolver((player, text) -> text.replace("%papi_x%", "<red>P</red>"));
        bundle.reload();
        final Component result = bundle.formatForPlayer(player(Locale.ENGLISH), "papi.msg", kv("name", "Bob"));
        assertEquals("Hi P Bob!", plain(result));
        assertTrue(MiniMessage.miniMessage().serialize(result).contains("<red>P</red>"));
    }

    @Test
    void formatForPlayerWithoutResolverUsesPlayerLocale(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.setLocaleResolver((player, def) -> player.locale());
        bundle.reload();
        assertEquals(
                "你好，Bob！",
                plain(bundle.formatForPlayer(player(Locale.SIMPLIFIED_CHINESE), "greeting", kv("name", "Bob"))));
        assertEquals(
                "Hello, Bob!", plain(bundle.formatForPlayer(player(Locale.ENGLISH), "greeting", kv("name", "Bob"))));
    }

    @Test
    void formatRawSubstitutesInResolvedTemplate(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("Hi Bob!", plain(bundle.formatRaw("Hi %name%!", Map.of("name", TextValue.mini("Bob")))));
    }

    @Test
    void rawChainAndRawForPlayer(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEnDe(folder);
        bundle.setLocaleResolver((player, def) -> player == null ? def : Locale.GERMANY);
        bundle.setPlaceholderResolver((player, text) -> text.replace("%papi_x%", "OK"));
        bundle.reload();
        assertEquals("English only", bundle.rawForPlayer(player(Locale.GERMANY), "only.en"));
        assertEquals("Hi OK %name%!", bundle.rawForPlayer(player(Locale.GERMANY), "papi.msg"));
        assertEquals("missing.key", bundle.rawForPlayer(player(Locale.GERMANY), "missing.key"));
    }

    @Test
    void rawForLocaleResolvesChainForGivenLocale(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEnDe(folder);
        bundle.reload();
        assertEquals("你好，%name%！", bundle.rawForLocale(Locale.SIMPLIFIED_CHINESE, "greeting"));
        assertEquals("Hello, %name%!", bundle.rawForLocale(Locale.ENGLISH, "greeting"));
        // de 缺失 → 默认 zh 缺失 → 回退 en。
        assertEquals("English only", bundle.rawForLocale(Locale.GERMANY, "only.en"));
        // 全部缺失 → 键名。
        assertEquals("missing.key", bundle.rawForLocale(Locale.GERMANY, "missing.key"));
    }

    // ── reload 与快照 ─────────────────────────────────────────────────────────

    @Test
    void reloadPicksUpDiskChanges(@TempDir final Path folder) throws Exception {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        assertEquals("你好，Bob！", plain(bundle.format("greeting", "name", "Bob")));
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/zh_CN.yml"), "greeting: \"更新后：%name%\"\n");
        bundle.reload();
        assertEquals("更新后：Bob", plain(bundle.format("greeting", "name", "Bob")));
    }

    @Test
    void rawMessagesSnapshot(@TempDir final Path folder) {
        final LanguageBundle bundle = zhEn(folder);
        bundle.reload();
        final Map<String, String> zh = bundle.rawMessages("zh_CN");
        assertEquals("你好，%name%！", zh.get("greeting"));
        assertThrows(UnsupportedOperationException.class, () -> zh.put("x", "y"));
        assertTrue(bundle.rawMessages("de_DE").isEmpty());
    }
}
