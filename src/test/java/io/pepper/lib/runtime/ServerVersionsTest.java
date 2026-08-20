package io.pepper.lib.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ServerVersions} 契约（自适应加载 §2.1）：
 * 新旧版本格式统一解析为数值数组，字典序比较；阈值 {@code [1,21,0]} 即
 * {@code InventoryView} 接口化版本（1.20.x 为 class，1.21+ 为 interface）。
 */
class ServerVersionsTest {

    @Test
    void parsesLegacyFormat() {
        assertArrayEquals(new int[] {1, 18, 2}, ServerVersions.parse("1.18.2"));
        assertArrayEquals(new int[] {1, 20, 1}, ServerVersions.parse("1.20.1"));
        assertArrayEquals(new int[] {1, 20, 6}, ServerVersions.parse("1.20.6"));
    }

    @Test
    void parsesTwoPartVersionAsPatchZero() {
        assertArrayEquals(new int[] {1, 21, 0}, ServerVersions.parse("1.21"));
        assertArrayEquals(new int[] {26, 1, 0}, ServerVersions.parse("26.1"));
    }

    @Test
    void parsesYearBasedFormat() {
        assertArrayEquals(new int[] {26, 1, 2}, ServerVersions.parse("26.1.2"));
        assertArrayEquals(new int[] {25, 3, 1}, ServerVersions.parse("25.3.1"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> ServerVersions.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ServerVersions.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ServerVersions.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> ServerVersions.parse("1.a.2"));
        assertThrows(IllegalArgumentException.class, () -> ServerVersions.parse("1.20.2.extra"));
    }

    @Test
    void isAtLeastTreatsLegacyVersionsBeforeInventoryViewInterface() {
        assertFalse(ServerVersions.isAtLeast(ServerVersions.parse("1.18.2"), ServerVersions.GUI_HOST_MIN_API));
        assertFalse(ServerVersions.isAtLeast(ServerVersions.parse("1.20.6"), ServerVersions.GUI_HOST_MIN_API));
    }

    @Test
    void isAtLeastAcceptsInventoryViewInterfaceVersion() {
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("1.21"), ServerVersions.GUI_HOST_MIN_API));
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("1.21.0"), ServerVersions.GUI_HOST_MIN_API));
    }

    @Test
    void isAtLeastComparesPatchWithinSameMinor() {
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("1.21.11"), ServerVersions.GUI_HOST_MIN_API));
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("1.21.4"), ServerVersions.GUI_HOST_MIN_API));
    }

    @Test
    void isAtLeastAcceptsYearBasedVersions() {
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("26.1.2"), ServerVersions.GUI_HOST_MIN_API));
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("25.1.0"), ServerVersions.GUI_HOST_MIN_API));
    }

    @Test
    void isAtLeastMajorComparisonDominates() {
        // 年份式主版本 26 > 旧式主版本 1：26.1.2 必须大于 1.21（数值比较而非字符串比较）。
        assertTrue(ServerVersions.isAtLeast(ServerVersions.parse("26.1.2"), ServerVersions.parse("1.21.0")));
        // 反向：1.21.0 不小于 26.1.2。
        assertFalse(ServerVersions.isAtLeast(ServerVersions.parse("1.21.0"), ServerVersions.parse("26.1.2")));
    }
}
