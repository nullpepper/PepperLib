package ltd.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ltd.pepper.lib.runtime.MinMinecraftVersion;
import ltd.pepper.lib.runtime.PepperLibRuntime;
import org.junit.jupiter.api.Test;

/**
 * world-instance 能力注解守卫：入口类型 {@link InstanceWorldService} 必须携带
 * {@link MinMinecraftVersion} 且归属 {@link PepperLibRuntime#CAP_WORLD_INSTANCE}——
 * 防止能力名与注解漂移导致前置插件能力决策与 API 实际可用性脱节。
 *
 * <p>与 gui-host 不同（1.21 阈值因 InventoryView 接口化），world-instance 公共签名
 * 只引用稳定 Bukkit 类型（{@code org.bukkit.World}），最低版本取库基线 1.18。</p>
 */
class WorldInstanceCapabilityGuardTest {

    @Test
    void capabilityConstantIsStable() {
        assertEquals("world-instance", PepperLibRuntime.CAP_WORLD_INSTANCE);
    }

    @Test
    void serviceEntryPointCarriesCapabilityAnnotation() {
        final MinMinecraftVersion annotation = InstanceWorldService.class.getAnnotation(MinMinecraftVersion.class);
        assertNotNull(annotation, "InstanceWorldService 必须标注 @MinMinecraftVersion");
        assertEquals("1.18", annotation.value(), "world-instance 公共签名无版本敏感类型，最低版本取库基线");
        assertEquals(PepperLibRuntime.CAP_WORLD_INSTANCE, annotation.capability());
    }
}
