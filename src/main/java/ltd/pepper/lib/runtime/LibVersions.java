package ltd.pepper.lib.runtime;

/**
 * lib 自身版本解析与比较（设计评审 §4.3）。
 *
 * <p>纯 Java 零依赖；semver 风格（1~3 段）统一解析为 {@code [major, minor, patch]}
 * 数值数组（缺段补 0），做「实际 &gt;= 最低」比较——替代消费者侧
 * {@code apiVersion().startsWith(...)} 前缀校验：0.x 阶段前缀语义近似 minor 契约，
 * 1.0 冻结后破坏性 major 升级会被前缀误放行，必须切换为最低版本比较。</p>
 */
public final class LibVersions {

    private LibVersions() {}

    /**
     * 解析 {@code "major[.minor[.patch]]"} 为数值数组（缺段补 0）。
     *
     * @param version 如 {@code "0.5.0"} / {@code "0.5"} / {@code "1"}
     * @return {@code [major, minor, patch]}
     * @throws IllegalArgumentException 输入为 null/空白/非数值/段数不为 1~3/含负号
     */
    public static int[] parse(final String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        final String[] parts = version.split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("invalid version: " + version);
        }
        final int[] parsed = new int[3];
        for (int i = 0; i < parts.length; i++) {
            parsed[i] = parsePart(parts[i], version);
        }
        return parsed;
    }

    private static int parsePart(final String part, final String whole) {
        try {
            final int value = Integer.parseInt(part);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid version: " + whole);
        }
    }

    /**
     * 运行时版本是否达到最低要求（数值字典序比较，缺段按 0 补）。
     *
     * @param actual 运行时版本（如 {@code apiVersion()} 返回值）
     * @param minimum 最低要求（消费者声明，如 {@code "0.5"}）
     * @return {@code actual >= minimum}
     * @throws IllegalArgumentException 任一输入非法
     */
    public static boolean atLeast(final String actual, final String minimum) {
        return isAtLeast(parse(actual), parse(minimum));
    }

    private static boolean isAtLeast(final int[] version, final int[] threshold) {
        for (int i = 0; i < 3; i++) {
            if (version[i] < threshold[i]) {
                return false;
            }
            if (version[i] > threshold[i]) {
                return true;
            }
        }
        return true;
    }
}
