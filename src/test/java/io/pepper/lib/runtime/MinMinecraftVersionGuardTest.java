package io.pepper.lib.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.gui.GuiHolder;
import io.pepper.lib.gui.GuiHost;
import io.pepper.lib.gui.PageHolderAdapter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 最低版本注解守卫（注解驱动能力决策引入）：gui-host 能力的三个类
 * （引用 {@code InventoryView}，1.20.x 为 class、1.21+ 为 interface）必须携带
 * {@link MinMinecraftVersion} 且阈值一致——防止标注漂移导致能力决策与类实际
 * 需求脱节（扫描器对同能力冲突取最严值，本测试保证一致）。
 */
class MinMinecraftVersionGuardTest {

    private static final List<Class<?>> GUI_HOST_CLASSES =
            List.of(GuiHolder.class, GuiHost.class, PageHolderAdapter.class);

    @Test
    void annotationIsRuntimeVisibleAndTypeTargeted() throws NoSuchMethodException {
        final Target target = MinMinecraftVersion.class.getAnnotation(Target.class);
        assertNotNull(target, "MinMinecraftVersion 必须声明 @Target(TYPE)");
        assertTrue(List.of(target.value()).contains(ElementType.TYPE), "注解必须可标注类型");
        final Retention retention = MinMinecraftVersion.class.getAnnotation(Retention.class);
        assertNotNull(retention, "MinMinecraftVersion 必须声明 @Retention(RUNTIME)");
        assertEquals(RetentionPolicy.RUNTIME, retention.value(), "前置插件需运行时反射读取");
    }

    @Test
    void allGuiHostClassesCarrySameMinVersion() {
        for (final Class<?> type : GUI_HOST_CLASSES) {
            final MinMinecraftVersion annotation = type.getAnnotation(MinMinecraftVersion.class);
            assertNotNull(annotation, type.getSimpleName() + " 必须标注 @MinMinecraftVersion");
            assertEquals("1.21", annotation.value(), type.getSimpleName() + " 最低版本必须为 1.21（InventoryView 接口化）");
            assertEquals(
                    PepperLibRuntime.CAP_GUI_HOST, annotation.capability(), type.getSimpleName() + " 必须归属 gui-host 能力");
        }
    }

    @Test
    void annotationValueParsesToInventoryViewThreshold() {
        assertArrayEquals(new int[] {1, 21, 0}, ServerVersions.parse("1.21"), "注解值必须能被 ServerVersions 解析为 gui-host 阈值");
    }
}
