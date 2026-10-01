package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 强一致性事务管理器（工作单元 Unit of Work 模式）。
 *
 * <p>确保跨多表的复杂业务在单条物理连接内原子提交或回滚，内置死锁与锁等待
 * （MySQL 1213 / 1205，SQLite BUSY 5）指数退避自动重试，杜绝数据撕裂与单边账。</p>
 */
public final class TransactionManager {

    private static final int MAX_DEADLOCK_RETRIES = 3;

    @FunctionalInterface
    public interface TxFunction<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface TxConsumer {
        void execute(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;

    public TransactionManager(final DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 在事务中执行带返回值的操作。
     *
     * @param action 事务操作
     * @param <T>    返回值类型
     * @return 操作结果
     * @throws StorageException 事务失败或重试耗尽
     */
    public <T> T inTransaction(final TxFunction<T> action) {
        Objects.requireNonNull(action, "action");
        int attempt = 0;
        while (true) {
            attempt++;
            try (Connection conn = this.dataSource.getConnection()) {
                final boolean origAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                boolean rollbackFailed = false;
                try {
                    final T result = action.execute(conn);
                    conn.commit();
                    return result;
                } catch (final SQLException ex) {
                    rollbackFailed = !rollbackQuietly(conn, ex);
                    // 回滚失败 ⇒ 连接状态未知：不得重试（会在坏连接上重放），直接上抛。
                    if (!rollbackFailed && isDeadlockOrBusy(ex) && attempt < MAX_DEADLOCK_RETRIES) {
                        try {
                            Thread.sleep(50L * (long) Math.pow(3, attempt - 1));
                        } catch (final InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new StorageException("事务死锁重试被中断", ie);
                        }
                        continue;
                    }
                    throw new StorageException("事务执行失败：" + ex.getMessage(), ex);
                } catch (final RuntimeException | Error failure) {
                    // 事务体抛运行时异常/错误也**必须回滚**：此前只捕 SQLException，于是异常穿过本块后，
                    // 下面 finally 的 setAutoCommit(true) 会按 JDBC 规范把这个**半截事务提交**掉
                    // （后果：主行已改、子行未重建，且没有任何报错——最难排查的一类）。
                    rollbackFailed = !rollbackQuietly(conn, failure);
                    throw failure;
                } finally {
                    // 回滚失败 ⇒ 连接状态未知，**不得**恢复 autoCommit（那会提交未回滚的事务）；
                    // 留给 try-with-resources 关闭，由连接池淘汰这条损坏连接。
                    if (!rollbackFailed) {
                        try {
                            conn.setAutoCommit(origAutoCommit);
                        } catch (final SQLException ignored) {
                        }
                    }
                }
            } catch (final SQLException connEx) {
                throw new StorageException("获取数据库事务连接失败：" + connEx.getMessage(), connEx);
            }
        }
    }

    /**
     * 在事务中执行无返回值的操作。
     */
    public void runInTransaction(final TxConsumer action) {
        Objects.requireNonNull(action, "action");
        inTransaction(conn -> {
            action.execute(conn);
            return null;
        });
    }

    /**
     * 在既有活动连接上嵌套执行事务。若连接已在事务中，则不改变其提交权。
     */
    public static <T> T inExistingConnection(final Connection conn, final TxFunction<T> action) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(action, "action");
        final boolean orig = conn.getAutoCommit();
        if (!orig) {
            // 已在外层事务中，直接执行
            return action.execute(conn);
        }
        conn.setAutoCommit(false);
        try {
            final T res = action.execute(conn);
            conn.commit();
            return res;
        } catch (final SQLException ex) {
            try {
                conn.rollback();
            } catch (final SQLException rbEx) {
                ex.addSuppressed(rbEx);
            }
            throw ex;
        } finally {
            try {
                conn.setAutoCommit(orig);
            } catch (final SQLException ignored) {
            }
        }
    }

    /** 静默回滚；失败时挂到原异常的 suppressed 上并返回 {@code false}（调用方据此放弃这条连接）。 */
    private static boolean rollbackQuietly(final Connection conn, final Throwable failure) {
        try {
            conn.rollback();
            return true;
        } catch (final SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private static boolean isDeadlockOrBusy(final SQLException e) {
        // 委托给全库唯一的瞬时错误口径（SqlExceptions.isTransient）。此处原先自带一份
        // code==1213||1205||5||state==40001 的判定，与 SqlExceptions.isBusyViolation 分叉：
        // 同一个库、同一类故障，走事务路径会重试，走 JdbcRetry 路径却不会。
        return SqlExceptions.isTransient(e);
    }
}
