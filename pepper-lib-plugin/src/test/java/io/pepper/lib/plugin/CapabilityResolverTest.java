package io.pepper.lib.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.runtime.PepperLibRuntime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilityResolver} 契约（注解驱动能力决策）：服务器版本 × 能力注册表
 * （capability → 最低版本，源自 {@code @MinMinecraftVersion} 扫描）→ 能力集的
 * 纯函数决策；阈值 {@code [1,21,0]}（InventoryView 接口化）。
 */
class CapabilityResolverTest {

    private static final Map<String, int[]> REGISTRY = Map.of(PepperLibRuntime.CAP_GUI_HOST, new int[] {1, 21, 0});

    @Test
    void legacyServersBeforeInventoryViewInterfaceDeclareNoGuiHost() {
        assertEquals(Set.of(), CapabilityResolver.resolve("1.18.2", REGISTRY));
        assertEquals(Set.of(), CapabilityResolver.resolve("1.20.6", REGISTRY));
    }

    @Test
    void inventoryViewInterfaceVersionDeclaresGuiHost() {
        assertEquals(Set.of(PepperLibRuntime.CAP_GUI_HOST), CapabilityResolver.resolve("1.21", REGISTRY));
        assertEquals(Set.of(PepperLibRuntime.CAP_GUI_HOST), CapabilityResolver.resolve("1.21.4", REGISTRY));
    }

    @Test
    void yearBasedVersionsDeclareGuiHost() {
        assertEquals(Set.of(PepperLibRuntime.CAP_GUI_HOST), CapabilityResolver.resolve("25.1.0", REGISTRY));
        assertEquals(Set.of(PepperLibRuntime.CAP_GUI_HOST), CapabilityResolver.resolve("26.1.2", REGISTRY));
    }

    @Test
    void resultIsUnmodifiable() {
        final Set<String> caps = CapabilityResolver.resolve("26.1.2", REGISTRY);
        assertTrue(caps.contains(PepperLibRuntime.CAP_GUI_HOST));
    }

    @Test
    void emptyRegistryDeclaresNothing() {
        assertEquals(Set.of(), CapabilityResolver.resolve("26.1.2", Map.of()));
    }

    @Test
    void higherThresholdCapabilityNotDeclaredOnLowerServer() {
        final Map<String, int[]> registry = Map.of("future-capability", new int[] {1, 22, 0});
        assertEquals(Set.of(), CapabilityResolver.resolve("1.21", registry));
        assertEquals(Set.of("future-capability"), CapabilityResolver.resolve("1.22", registry));
    }
}
