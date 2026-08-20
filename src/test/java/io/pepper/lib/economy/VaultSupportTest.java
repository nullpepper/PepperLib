package io.pepper.lib.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pepper.lib.i18n.FakePapiPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * {@link VaultSupport} 软依赖解析测试：未注册 → null；注册后返回提供者。
 */
class VaultSupportTest {

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
    void returnsNullWhenNoVaultServiceRegistered() {
        assertNull(VaultSupport.economy());
    }

    @Test
    void returnsRegisteredEconomyProvider() {
        final Economy economy = Mockito.mock(Economy.class);
        final Plugin plugin = MockBukkit.load(FakePapiPlugin.class);
        this.server.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        assertEquals(economy, VaultSupport.economy());
    }
}
