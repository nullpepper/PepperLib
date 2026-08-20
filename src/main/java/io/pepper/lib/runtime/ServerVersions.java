package io.pepper.lib.runtime;

/**
 * 服务器版本解析与比较（自适应加载 §2.1）。
 *
 * <p>纯 Java 零 Bukkit 引用；统一处理新旧版本格式：旧式 {@code "1.18.2"} 与
 * 年份式 {@code "26.1.2"} 均解析为 {@code [major, minor, patch]} 数值数组，
 * 字典序比较（主版本 26 &gt; 1，天然正确）。</p>
 */
public final class ServerVersions {

    /** gui-host 特性所需的最低 API 版本：MC 1.21（{@code InventoryView} 接口化版本）。 */
    public static final int[] GUI_HOST_MIN_API = {1, 21, 0};

    private ServerVersions() {}

    /**
     * 解析 {@code "major.minor[.patch]"} 为数值数组（缺省 patch 补 0）。
     *
     * @param minecraftVersion {@code Bukkit.getMinecraftVersion()} 返回值（如 {@code "1.18.2"}）
     * @return {@code [major, minor, patch]}
     * @throws IllegalArgumentException 输入为 null/空白/非数值/段数不为 2~3
     */
    public static int[] parse(final String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraft version must not be blank");
        }
        final String[] parts = minecraftVersion.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("invalid minecraft version: " + minecraftVersion);
        }
        final int[] version = new int[3];
        for (int i = 0; i < parts.length; i++) {
            version[i] = parsePart(parts[i], minecraftVersion);
        }
        return version;
    }

    private static int parsePart(final String part, final String whole) {
        try {
            final int value = Integer.parseInt(part);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid minecraft version: " + whole);
        }
    }

    /**
     * 数值数组字典序比较：{@code version >= threshold}。
     *
     * @param version 运行时版本（{@link #parse(String)} 结果）
     * @param threshold 阈值（如 {@link #GUI_HOST_MIN_API}）
     * @return 是否达到阈值
     */
    public static boolean isAtLeast(final int[] version, final int[] threshold) {
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
