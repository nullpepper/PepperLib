package ltd.pepper.lib.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ltd.pepper.lib.placeholder.PlaceholderSupport;
import ltd.pepper.placeholder.PlaceholderEngine;
import ltd.pepper.placeholder.PlaceholderEngine.Ctx;
import ltd.pepper.placeholder.PlaceholderEngine.Registry;
import ltd.pepper.placeholder.PlaceholderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * {@link EnginePlaceholderResolver} 与 {@link LanguageBundle} 的集成测试。
 *
 * <p>锁定「路径 B」契约：注册表占位符在<b>字符串级</b>由引擎解析、调用期参数在
 * <b>Component 级</b>由 i18n 层替换，两者共用 {@code ${}} 定界符且互不干扰。</p>
 */
class EnginePlaceholderResolverTest {

    private static final List<String> ZH_EN = List.of("zh_CN", "en_US");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 模拟 PepperPlaceholder 插件启用：把引擎经 ServicesManager 暴露出去。 */
    private void installEngineHost() {
        final Plugin host = MockBukkit.createMockPlugin("PepperPlaceholder");
        this.server
                .getServicesManager()
                .register(
                        PlaceholderService.class,
                        new TestService(new PlaceholderEngine()),
                        host,
                        ServicePriority.Normal);
    }

    private static InputStream resource(final String path) {
        return EnginePlaceholderResolverTest.class.getClassLoader().getResourceAsStream(path);
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Player player(final Locale locale) {
        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.locale()).thenReturn(locale);
        return player;
    }

    private LanguageBundle bundle(final Path folder) {
        final LanguageBundle bundle =
                new LanguageBundle(folder, EnginePlaceholderResolverTest::resource, ZH_EN, "zh_CN", "en_US");
        bundle.setPlaceholderResolver(EnginePlaceholderResolver.INSTANCE);
        bundle.reload();
        return bundle;
    }

    @Test
    void registryPlaceholderAndCallTimeParamCoexistInOneTemplate(@TempDir final Path folder) {
        installEngineHost();
        // 模板 papi.msg = "Hi ${papi_x} ${name}!"：前者是注册表占位符（引擎解析），
        // 后者是调用期参数（i18n 层替换）。未注册的 ${name} 必须原样穿过引擎。
        PlaceholderSupport.register("papi", (params, player) -> params.equals("x") ? "<red>P</red>" : null);
        final LanguageBundle bundle = bundle(folder);
        final Component result =
                bundle.formatForPlayer(player(Locale.ENGLISH), "papi.msg", Map.of("name", TextValue.mini("Bob")));
        assertEquals("Hi P Bob!", plain(result));
        assertTrue(MiniMessage.miniMessage().serialize(result).contains("<red>P</red>"));
    }

    @Test
    void unknownPlaceholderStaysLiteralThroughBothLayers(@TempDir final Path folder) {
        installEngineHost();
        // 引擎未注册 union → 原样保留；i18n 层也没有该参数 → 最终仍是字面量。
        final LanguageBundle bundle = bundle(folder);
        assertEquals(
                "你好，${name}！", plain(bundle.formatForPlayer(player(Locale.SIMPLIFIED_CHINESE), "greeting", Map.of())));
    }

    @Test
    void nullPlayerLeavesTemplateUntouched(@TempDir final Path folder) {
        installEngineHost();
        PlaceholderSupport.register("papi", (params, player) -> params.equals("x") ? "P" : null);
        final LanguageBundle bundle = bundle(folder);
        // 无玩家上下文（控制台/RCON 发送者）必须整体跳过引擎：
        // 引擎对 null 玩家会把 ${player} 解析成空串，而 ${player} 在本插件里是
        // 调用期参数（见 PepperUnion lang），被吃掉就等于丢参数。
        assertEquals("Hi ${papi_x} ${name}!", bundle.rawForPlayer(null, "papi.msg"));
    }

    @Test
    void consoleRenderKeepsCallTimeParams(@TempDir final Path folder) {
        // 控制台发送者走的就是这条路径（PepperUnion CommandContext.formatForSender）。
        installEngineHost();
        PlaceholderSupport.register("player", (params, player) -> "");
        final LanguageBundle bundle = bundle(folder);
        final Component result = bundle.formatForPlayer(null, "console.msg", Map.of("player", TextValue.mini("Bob")));
        assertEquals("玩家 Bob 上线", plain(result));
    }

    @Test
    void degradesToLiteralWhenEngineHostAbsent(@TempDir final Path folder) {
        // 不装引擎宿主：解析器必须原样放行，绝不抛异常。
        final LanguageBundle bundle = bundle(folder);
        assertEquals("Hi ${papi_x} ${name}!", bundle.rawForPlayer(null, "papi.msg"));
    }

    /** 最小 {@link PlaceholderService} 实现：直接转发到真实引擎（模拟 Paper 适配层）。 */
    private record TestService(PlaceholderEngine engine) implements PlaceholderService {

        @Override
        public PlaceholderEngine engine() {
            return this.engine;
        }

        @Override
        public Registry global() {
            return this.engine.global();
        }

        @Override
        public Registry registry(final String name) {
            return this.engine.newRegistry(name);
        }

        @Override
        public String resolve(final String template, final Ctx ctx) {
            return this.engine.resolve(template, ctx);
        }

        @Override
        public String resolve(final String template, final Ctx ctx, final Registry... registries) {
            return this.engine.resolve(template, ctx, registries);
        }
    }
}
