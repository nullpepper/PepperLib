package io.pepper.lib.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pepper.lib.runtime.PepperLibRuntime;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 前置插件测试（双模式重构文档 §9.2）：启动、服务注册/注销、版本与能力诊断。
 */
class PepperLibPluginTest {

    private ServerMock server;
    private PepperLibPlugin plugin;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.plugin = MockBukkit.load(PepperLibPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesAndRegistersRuntimeService() {
        final RegisteredServiceProvider<PepperLibRuntime> provider =
                this.server.getServicesManager().getRegistration(PepperLibRuntime.class);
        assertNotNull(provider, "PepperLibRuntime must be registered via ServicesManager");
        final PepperLibRuntime runtime = provider.getProvider();
        assertEquals("0.5.0", runtime.apiVersion());
        assertFalse(runtime.supports("nonexistent-capability"));
    }

    @Test
    void disableUnregistersRuntimeService() {
        this.plugin.onDisable();
        assertNull(this.server.getServicesManager().getRegistration(PepperLibRuntime.class));
    }
}
