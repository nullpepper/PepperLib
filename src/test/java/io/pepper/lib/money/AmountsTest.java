package io.pepper.lib.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link Amounts} 测试（设计文档 docs/extraction-a2-plan.md §2.4）：
 * 两插件断言并集——Claim 的 toCents/formatFixed HALF_UP 语义 + Union 的
 * format 去尾零 / tryParse / isValid / toMajor 2^53 守卫语义。
 */
class AmountsTest {

    // ── 分 ↔ 元（Claim 语义）──────────────────────────────────────────────────

    @Test
    void centsToMajorKeepsTwoDecimals() {
        assertEquals(1.23, Amounts.toMajor(123));
        assertEquals(0.0, Amounts.toMajor(0));
        assertEquals(100.5, Amounts.toMajor(10_050));
        assertEquals(0.01, Amounts.toMajor(1));
    }

    @Test
    void majorToCentsRoundsHalfUp() {
        assertEquals(123, Amounts.toCents(1.23));
        assertEquals(124, Amounts.toCents(1.235));
        assertEquals(123, Amounts.toCents(1.234));
        assertEquals(100, Amounts.toCents(1.0));
        assertEquals(0, Amounts.toCents(0.001));
        assertEquals(1, Amounts.toCents(0.009));
    }

    @Test
    void bigDecimalToCents() {
        assertEquals(123, Amounts.toCents(new BigDecimal("1.23")));
        assertEquals(125, Amounts.toCents(new BigDecimal("1.245")));
    }

    // ── 2^53 守卫（Union 语义）────────────────────────────────────────────────

    @Test
    void toMajorRejectsBeyondDoubleExactRange() {
        assertThrows(ArithmeticException.class, () -> Amounts.toMajor(9_007_199_254_740_993L));
        assertThrows(ArithmeticException.class, () -> Amounts.toMajor(-9_007_199_254_740_993L));
        // 2^53 分 = 2^53 / 100 元，恰好落在精确范围内。
        assertEquals(9_007_199_254_740_992L / 100.0, Amounts.toMajor(9_007_199_254_740_992L));
    }

    // ── 显示（双变体）──────────────────────────────────────────────────────────

    @Test
    void formatStripsTrailingZeros() {
        assertEquals("-92233720368547758.08", Amounts.format(Long.MIN_VALUE));
        assertEquals("0", Amounts.format(0L));
        assertEquals("100.5", Amounts.format(10050L));
        assertEquals("-0.5", Amounts.format(-50L));
    }

    @Test
    void formatFixedProducesPlainTwoDecimalString() {
        assertEquals("1.23", Amounts.formatFixed(123));
        assertEquals("0.00", Amounts.formatFixed(0));
        assertEquals("0.01", Amounts.formatFixed(1));
        assertEquals("100.50", Amounts.formatFixed(10_050));
    }

    @Test
    void formatDoubleRejectsNonFiniteAsZero() {
        assertEquals("0", Amounts.format(Double.NaN));
        assertEquals("0", Amounts.format(Double.POSITIVE_INFINITY));
        assertEquals("0", Amounts.format(Double.NEGATIVE_INFINITY));
        assertEquals("100.5", Amounts.format(100.5));
        assertEquals("1", Amounts.format(1.0));
    }

    // ── 校验 ──────────────────────────────────────────────────────────────────

    @Test
    void isValidRejectsLongMinValueWithoutAbsOverflow() {
        assertFalse(Amounts.isValid(Long.MIN_VALUE));
        assertFalse(Amounts.isValid(-Amounts.MAX_CENTS - 1));
        assertTrue(Amounts.isValid(-Amounts.MAX_CENTS));
        assertTrue(Amounts.isValid(Amounts.MAX_CENTS));
    }

    @Test
    void isValidWithExplicitMaxAbsBound() {
        // 参数化形态：域边界（如 Claim 旧 9e15）由插件传入；语义为绝对值上限。
        assertTrue(Amounts.isValid(0, 9_000_000_000_000_000L));
        assertTrue(Amounts.isValid(-1, 9_000_000_000_000_000L));
        assertTrue(Amounts.isValid(9_000_000_000_000_000L, 9_000_000_000_000_000L));
        assertFalse(Amounts.isValid(9_000_000_000_000_001L, 9_000_000_000_000_000L));
        assertFalse(Amounts.isValid(-9_000_000_000_000_001L, 9_000_000_000_000_000L));
    }

    // ── 解析（Union 语义）──────────────────────────────────────────────────────

    @Test
    void tryParseRejectsLongMinValueWithoutAbsOverflow() {
        assertTrue(Amounts.tryParse(String.valueOf(Long.MIN_VALUE)).isEmpty());
        assertTrue(Amounts.tryParse("-92233720368547758.08").isEmpty());
        // tryParse 的输入是"货币单位"（最多两位小数），输出分：
        // MAX_CENTS 分 = MAX_CENTS / 100 个货币单位，恰好落在上限内。
        assertTrue(Amounts.tryParse(String.valueOf(-Amounts.MAX_CENTS / 100)).isPresent());
        // 而 MAX_CENTS 本身作为货币单位输入会换算成 100 倍的分数，超限被拒。
        assertTrue(Amounts.tryParse(String.valueOf(-Amounts.MAX_CENTS)).isEmpty());
        assertTrue(Amounts.tryParse(String.valueOf(Amounts.MAX_CENTS)).isEmpty());
    }

    @Test
    void tryParseRejectsMalformedInput() {
        assertTrue(Amounts.tryParse(null).isEmpty());
        assertTrue(Amounts.tryParse("").isEmpty());
        assertTrue(Amounts.tryParse("  ").isEmpty());
        assertTrue(Amounts.tryParse("1E+30").isEmpty());
        assertTrue(Amounts.tryParse("1e5").isEmpty());
        assertTrue(Amounts.tryParse("1.235").isEmpty());
        assertTrue(Amounts.tryParse("abc").isEmpty());
        assertEquals(123, Amounts.tryParse("1.23").orElseThrow());
        assertEquals(-50, Amounts.tryParse("-0.5").orElseThrow());
    }

    @Test
    void tryParseRejectsOversizedInput() {
        // 超长数字串（如被塞入 40 位零）会触发昂贵 BigDecimal 解析：长度上限直接拒绝。
        // "0".repeat(40) 数值合法且不溢出，当前实现会解析成功——上限修复后必须拒绝。
        assertTrue(Amounts.tryParse("0".repeat(40)).isEmpty());
        assertTrue(Amounts.tryParse("1".repeat(40)).isEmpty());
        // 正常输入不受影响。
        assertTrue(Amounts.tryParse("123.45").isPresent());
        assertTrue(Amounts.tryParse("9999999999999").isPresent());
    }
}
