package io.pepper.lib.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link PapiPlaceholderResolver} 软依赖守卫测试（设计文档 §11 #17）：
 * 未安装/未启用 → 原样；jar 在场且插件启用但未注册扩展 → 原样（PAPI 静态未注册早退）。
 */
class PapiPlaceholderResolverTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void passesThroughWhenPapiNotInstalled() {
        final Player player = this.server.addPlayer("alice");
        assertEquals("Hi %papi_x%", PapiPlaceholderResolver.INSTANCE.resolve(player, "Hi %papi_x%"));
        assertEquals("plain", PapiPlaceholderResolver.INSTANCE.resolve(player, "plain"));
        assertEquals("Hi %papi_x%", PapiPlaceholderResolver.INSTANCE.resolve(null, "Hi %papi_x%"));
    }

    @Test
    void passesThroughWhenPapiPluginDisabled() {
        final Plugin papi = MockBukkit.load(FakePapiPlugin.class);
        this.server.getPluginManager().disablePlugin(papi);
        final Player player = this.server.addPlayer("alice");
        assertEquals("Hi %papi_x%", PapiPlaceholderResolver.INSTANCE.resolve(player, "Hi %papi_x%"));
    }

    @Test
    void resolvesThroughPapiWhenPresentAndEnabled() {
        MockBukkit.load(FakePapiPlugin.class);
        final Player player = this.server.addPlayer("alice");
        // PAPI jar 在场且插件“已启用”，但未注册任何扩展：setPlaceholders 原样返回。
        assertEquals("Hi %papi_x%", PapiPlaceholderResolver.INSTANCE.resolve(player, "Hi %papi_x%"));
    }
}
