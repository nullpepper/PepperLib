package io.pepper.lib.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 金额工具（统一自两插件 util.Amounts，内部设计文档 extraction-a2 §2）。
 *
 * <p>金额边界：持久化/服务内部一律分（long），Vault/渲染边界用 BigDecimal 换算。
 * 显示双变体：{@link #format(long)} 去尾零（PepperUnion 语义），
 * {@link #formatFixed(long)} 固定两位小数（PepperClaim 语义）。</p>
 */
public final class Amounts {

    /** 账户金额的最大绝对值（分，即 1e13 个货币单位）。 */
    public static final long MAX_CENTS = 1_000_000_000_000_000L;

    /** 配置金额的最大绝对值（1e15 货币单位）。防止巨大科学计数法产生天文数字字符串。 */
    public static final double MAX_AMOUNT = 1e15;

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** Vault 是 double 型；超出 double 整数精度（2^53≈9.007e15 分）拒绝转换，避免静默丢精度。 */
    private static final long MAX_VAULT_EXACT_CENTS = 9_007_199_254_740_992L;

    /** {@link #tryParse(String)} 输入长度上限（防超长数字串触发昂贵 BigDecimal 解析）。 */
    private static final int MAX_INPUT_LENGTH = 32;

    private Amounts() {}

    /** 元 → 分（HALF_UP 舍入，货币单位最多两位小数）。 */
    public static long toCents(final double major) {
        return BigDecimal.valueOf(major)
                .multiply(ONE_HUNDRED)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** BigDecimal 元 → 分（HALF_UP 舍入）。 */
    public static long toCents(final BigDecimal major) {
        return major.multiply(ONE_HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 分 → 元（double）；超出 double 整数精度（2^53）拒绝转换，
     * 避免超大金额经 Vault 往返时静默舍入。
     *
     * <p>用范围比较而非 Math.abs，防止 Long.MIN_VALUE 取绝对值溢出。</p>
     */
    public static double toMajor(final long cents) {
        if (cents > MAX_VAULT_EXACT_CENTS || cents < -MAX_VAULT_EXACT_CENTS) {
            throw new ArithmeticException("cents too large for double: " + cents);
        }
        return cents / 100.0;
    }

    /** 分的展示文本（去尾零）：10050 -&gt; "100.5"，-50 -&gt; "-0.5"。 */
    public static String format(final long cents) {
        // 使用 BigDecimal 而非 Math.abs：Long.MIN_VALUE 取绝对值会溢出。
        return BigDecimal.valueOf(cents).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    /** 分的展示文本（固定两位小数）：10050 -&gt; "100.50"，0 -&gt; "0.00"。 */
    public static String formatFixed(final long cents) {
        return BigDecimal.valueOf(cents)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * 配置金额的展示文本（去尾零）。NaN / Infinity（配置或输入异常）一律按 {@code 0} 显示，
     * 避免把非法值渲染进玩家可见消息。
     */
    public static String format(final double amount) {
        if (!Double.isFinite(amount)) {
            return "0";
        }
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    /**
     * 解析用户输入的金额为分。拒绝空串、科学计数法（如 {@code 1E+30}，会解析为
     * Infinity）、超过两位小数、超出 {@link #MAX_CENTS} 的值、以及超过 32 字符的
     * 超长输入（防昂贵解析）。使用 {@link BigDecimal} 解析，避免二进制浮点舍入污染金额。
     */
    public static Optional<Long> tryParse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.length() > MAX_INPUT_LENGTH) {
            // 超长数字串（玩家可输入）会触发昂贵的 BigDecimal 解析：长度上限直接拒绝。
            return Optional.empty();
        }
        if (trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
            return Optional.empty();
        }
        try {
            final BigDecimal value = new BigDecimal(trimmed);
            if (value.stripTrailingZeros().scale() > 2) {
                return Optional.empty();
            }
            final long cents = value.movePointRight(2).longValueExact();
            if (cents > MAX_CENTS || cents < -MAX_CENTS) {
                return Optional.empty();
            }
            return Optional.of(cents);
        } catch (final NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /** 金额是否合法：绝对值不超过 {@link #MAX_CENTS}（Long.MIN_VALUE 安全）。 */
    public static boolean isValid(final long cents) {
        return cents >= 0 ? cents <= MAX_CENTS : cents >= -MAX_CENTS;
    }

    /** 金额是否合法：绝对值不超过指定上限（域边界由插件传入）。 */
    public static boolean isValid(final long cents, final long maxAbsCents) {
        return cents >= 0 ? cents <= maxAbsCents : cents >= -maxAbsCents;
    }
}
