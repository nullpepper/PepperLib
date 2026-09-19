package ltd.pepper.lib.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ltd.pepper.placeholder.PlaceholderEngine;
import ltd.pepper.placeholder.PlaceholderEngine.Ctx;
import ltd.pepper.placeholder.PlaceholderEngine.Registry;
import ltd.pepper.placeholder.PlaceholderService;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link PlaceholderSupport} 原生占位符路径测试：经 MockBukkit ServicesManager
 * 注册真实 {@link PlaceholderEngine}，验证「注册 → 解析」端到端可用，
 * 以及宿主缺失时的软依赖降级（不抛异常、原样返回）。
 */
class PlaceholderSupportTest {

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

    @Test
    void registersAndResolvesThroughNativeEngine() {
        installEngineHost();
        assertTrue(PlaceholderSupport.available(), "引擎宿主在场时 available() 应为 true");
        assertTrue(PlaceholderSupport.register("demo", (params, player) -> "V:" + params));
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("x V:a y", PlaceholderSupport.resolve(player, "x ${demo_a} y"));
        assertEquals("V:", PlaceholderSupport.resolve(player, "${demo}"));
        assertTrue(PlaceholderSupport.identifiers().contains("demo"));
    }

    @Test
    void unregisteredPlaceholderIsPreservedVerbatim() {
        installEngineHost();
        PlaceholderSupport.register("demo", (params, player) -> "V:" + params);
        final OfflinePlayer player = this.server.addPlayer("alice");
        // 调用期参数（i18n 内部占位符）与注册表占位符共存：未注册的原样保留。
        assertEquals("A ${name} B V:x", PlaceholderSupport.resolve(player, "A ${name} B ${demo_x}"));
    }

    @Test
    void handlerNullKeepsPlaceholderVerbatim() {
        installEngineHost();
        PlaceholderSupport.register("demo", (params, player) -> null);
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("${demo_a}", PlaceholderSupport.resolve(player, "${demo_a}"));
    }

    @Test
    void paramsAreOpaqueAndSplitAtFirstUnderscore() {
        installEngineHost();
        PlaceholderSupport.register("garbage", (params, player) -> "[" + params + "]");
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("[bin_count_public]", PlaceholderSupport.resolve(player, "${garbage_bin_count_public}"));
    }

    @Test
    void playerReachesHandler() {
        installEngineHost();
        PlaceholderSupport.register("who", (params, player) -> player == null ? "none" : player.getName());
        final OfflinePlayer alice = this.server.addPlayer("alice");
        assertEquals("alice", PlaceholderSupport.resolve(alice, "${who}"));
        assertEquals("none", PlaceholderSupport.resolve(null, "${who}"));
    }

    @Test
    void unregisterRemovesPlaceholder() {
        installEngineHost();
        PlaceholderSupport.register("demo", (params, player) -> "V:" + params);
        assertTrue(PlaceholderSupport.unregister("demo"));
        assertFalse(PlaceholderSupport.unregister("demo"), "重复注销应返回 false");
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("${demo_a}", PlaceholderSupport.resolve(player, "${demo_a}"));
    }

    @Test
    void reRegisterReplacesInsteadOfThrowing() {
        installEngineHost();
        PlaceholderSupport.register("demo", (params, player) -> "first");
        // 热重载场景：同名重复注册必须替换而不是抛 IllegalStateException。
        assertTrue(PlaceholderSupport.register("demo", (params, player) -> "second"));
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("second", PlaceholderSupport.resolve(player, "${demo}"));
    }

    @Test
    void identifierWithUnderscoreIsRejectedNotThrown() {
        installEngineHost();
        assertFalse(PlaceholderSupport.register("bad_id", (params, player) -> "x"));
        assertFalse(PlaceholderSupport.identifiers().contains("bad_id"));
    }

    @Test
    void degradesSilentlyWhenEngineHostAbsent() {
        assertFalse(PlaceholderSupport.available());
        assertFalse(PlaceholderSupport.register("demo", (params, player) -> "V:" + params));
        assertFalse(PlaceholderSupport.unregister("demo"));
        assertTrue(PlaceholderSupport.identifiers().isEmpty());
        final OfflinePlayer player = this.server.addPlayer("alice");
        assertEquals("${demo_a}", PlaceholderSupport.resolve(player, "${demo_a}"));
    }

    @Test
    void resolveHandlesNullAndPlainText() {
        installEngineHost();
        PlaceholderSupport.register("demo", (params, player) -> "V");
        assertEquals("plain", PlaceholderSupport.resolve(null, "plain"));
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
