package io.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC 写操作有限次退避重试（源自 PepperBotBindManager {@code BindManagerImpl} 提取）。
 *
 * <p>只对 {@link SqlExceptions#isBusyViolation transient busy} 异常重试
 * （如 SQLite 单写者场景的偶发 {@code SQLITE_BUSY}），唯一键冲突等非 transient
 * 异常立即上抛。每次尝试重新获取连接（{@link ConnectionSupplier}），退避时长
 * {@code baseDelayMs * attempt}，中断时恢复中断位并上抛当前异常。</p>
 */
public final class JdbcRetry {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BASE_DELAY_MS = 50;

    /** 带检查异常 {@link SQLException} 的连接获取。 */
    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    /** 带检查异常 {@link SQLException} 的 JDBC 操作。 */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private JdbcRetry() {}

    /** 默认 3 次尝试（含首次）、基础退避 50ms。 */
    public static <T> T withConnectionRetry(ConnectionSupplier connections, SqlFunction<T> action) throws SQLException {
        return withConnectionRetry(connections, action, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS);
    }

    /**
     * 有限次退避重试执行。
     *
     * @param connections 每次尝试重新获取连接
     * @param action      要执行的 JDBC 操作
     * @param maxRetries  最大尝试次数（含首次，须 &gt; 0）
     * @param baseDelayMs 基础退避毫秒数（实际退避 = baseDelayMs * attempt）
     * @return action 的返回值
     * @throws SQLException 非 busy 异常立即上抛；busy 重试耗尽后抛最后一次异常
     */
    public static <T> T withConnectionRetry(
            ConnectionSupplier connections, SqlFunction<T> action, int maxRetries, long baseDelayMs)
            throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Connection conn = connections.get()) {
                return action.apply(conn);
            } catch (SQLException e) {
                last = e;
                if (!SqlExceptions.isBusyViolation(e) || attempt == maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(baseDelayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
