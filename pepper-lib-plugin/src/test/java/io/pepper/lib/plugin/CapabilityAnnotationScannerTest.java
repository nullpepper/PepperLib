package io.pepper.lib.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pepper.lib.runtime.PepperLibRuntime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilityAnnotationScanner} 契约：扫描 classpath 中 {@code io.pepper.lib}
 * 类的 {@code @MinMinecraftVersion} 注解，构建 {@code capability → 最低版本} 注册表。
 * 测试运行于真实 classpath（lib classes 目录/jar 两种形态均需支持）。
 */
class CapabilityAnnotationScannerTest {

    @Test
    void scansClasspathForAnnotatedClasses() {
        final Map<String, int[]> registry =
                CapabilityAnnotationScanner.scan(CapabilityAnnotationScanner.class.getClassLoader());
        assertArrayEquals(
                new int[] {1, 21, 0},
                registry.get(PepperLibRuntime.CAP_GUI_HOST),
                "gui-host 必须从 GuiHolder/GuiHost/PageHolderAdapter 的注解聚合为 [1,21,0]");
    }

    @Test
    void registryContainsOnlyAnnotatedCapabilities() {
        final Map<String, int[]> registry =
                CapabilityAnnotationScanner.scan(CapabilityAnnotationScanner.class.getClassLoader());
        assertEquals(Set.of(PepperLibRuntime.CAP_GUI_HOST), registry.keySet());
    }
}
