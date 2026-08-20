package io.pepper.lib.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link LibVersions} 契约（设计评审 §4.3）：lib 自身版本（semver 风格，1~3 段）
 * 解析为数值数组并做「实际 &gt;= 最低」比较——替代消费者侧 {@code startsWith} 前缀
 * 校验，为 1.0 冻结后的版本语义做准备。
 */
class LibVersionsTest {

    @Test
    void parsesOneToThreeSegments() {
        assertArrayEquals(new int[] {0, 5, 0}, LibVersions.parse("0.5.0"));
        assertArrayEquals(new int[] {0, 5, 0}, LibVersions.parse("0.5"));
        assertArrayEquals(new int[] {1, 0, 0}, LibVersions.parse("1"));
        assertArrayEquals(new int[] {0, 6, 2}, LibVersions.parse("0.6.2"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse(null));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse(""));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse("0.5."));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse("0.5.0.1"));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.parse("0.-5"));
    }

    @Test
    void atLeastAcceptsMissingPatchSegments() {
        assertTrue(LibVersions.atLeast("0.5.0", "0.5"));
        assertTrue(LibVersions.atLeast("0.5", "0.5.0"));
        assertTrue(LibVersions.atLeast("1.0.0", "1"));
    }

    @Test
    void atLeastComparesNumerically() {
        assertTrue(LibVersions.atLeast("0.6.0", "0.5.0"));
        assertTrue(LibVersions.atLeast("0.5.3", "0.5.2"));
        assertTrue(LibVersions.atLeast("1.0.0", "0.5.0"));
        assertFalse(LibVersions.atLeast("0.4.9", "0.5"));
        assertFalse(LibVersions.atLeast("0.5.2", "0.5.3"));
        assertFalse(LibVersions.atLeast("0.5.0", "1.0.0"));
        assertTrue(LibVersions.atLeast("0.5.0", "0.5.0"));
    }

    @Test
    void atLeastRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> LibVersions.atLeast("0.5.0", null));
        assertThrows(IllegalArgumentException.class, () -> LibVersions.atLeast("nope", "0.5"));
    }
}
