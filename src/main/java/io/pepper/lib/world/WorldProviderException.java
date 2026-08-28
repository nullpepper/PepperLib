package io.pepper.lib.world;

/**
 * 实例世界操作失败异常：携带稳定错误码 {@link WorldProviderError}，
 * 供调用方按码分支处理；provider 内部细节经 {@code message} 与 {@code cause} 保留。
 */
public class WorldProviderException extends RuntimeException {

    private final WorldProviderError error;

    /**
     * @param error   稳定错误码（非 null）
     * @param message 人类可读的诊断信息
     */
    public WorldProviderException(final WorldProviderError error, final String message) {
        super(message);
        this.error = java.util.Objects.requireNonNull(error, "error");
    }

    /**
     * @param error   稳定错误码（非 null）
     * @param message 人类可读的诊断信息
     * @param cause   底层原因（如 ASWM 异常、IO 异常）
     */
    public WorldProviderException(final WorldProviderError error, final String message, final Throwable cause) {
        super(message, cause);
        this.error = java.util.Objects.requireNonNull(error, "error");
    }

    /** @return 稳定错误码 */
    public WorldProviderError error() {
        return this.error;
    }
}
