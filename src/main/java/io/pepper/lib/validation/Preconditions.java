package io.pepper.lib.validation;

/**
 * 无业务含义的参数校验工具。
 *
 * <p>只放与业务无关的通用检查；金额、权限、领域约束等业务校验留在插件内。</p>
 */
public final class Preconditions {

    private Preconditions() {}

    /**
     * 校验值非空。
     *
     * @param value 被校验的值
     * @param name 参数名（用于异常消息）
     * @return 原值
     * @throws NullPointerException 值为 {@code null}
     */
    public static <T> T requireNonNull(final T value, final String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }

    /**
     * 校验整数非负。
     *
     * @param value 被校验的值
     * @param name 参数名（用于异常消息）
     * @return 原值
     * @throws IllegalArgumentException 值为负数
     */
    public static int requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got " + value);
        }
        return value;
    }

    /**
     * 校验整数为正。
     *
     * @param value 被校验的值
     * @param name 参数名（用于异常消息）
     * @return 原值
     * @throws IllegalArgumentException 值不为正
     */
    public static int requirePositive(final int value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    /**
     * 校验字符串非空且非空白。
     *
     * @param value 被校验的值
     * @param name 参数名（用于异常消息）
     * @return 原值
     * @throws IllegalArgumentException 值为 {@code null}、空串或全空白
     */
    public static String requireNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
