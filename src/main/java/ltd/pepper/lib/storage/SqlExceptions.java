package ltd.pepper.lib.storage;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;

/**
 * JDBC {@link SQLException} 分类工具（源自 PepperBotBindManager {@code BindManagerImpl} 提取）。
 *
 * <p>把「写操作失败」区分成两类关键语义：</p>
 * <ul>
 *   <li><b>唯一键冲突</b>（SQLState 23xxx 系列，含 SQLite 23505 / MySQL 23000，
 *       以及消息文本兜底）——业务上应映射为「已存在」，不可重试；</li>
 *   <li><b>transient 繁忙</b>（SQLite errorCode 5 / {@code sqlite_busy} /
 *       {@code database is locked}）——可配合 {@link JdbcRetry} 有限次退避重试。</li>
 * </ul>
 */
public final class SqlExceptions {

    private SqlExceptions() {}

    /**
     * 是否为唯一键/主键冲突：优先看 SQLState 23xxx 与 MySQL/MariaDB 错误码
     * {@code 1062}（ER_DUP_ENTRY，SQLState 可能缺失），消息兜底大小写不敏感，
     * 覆盖 SQLite {@code UNIQUE constraint failed}、MySQL {@code Duplicate entry}、
     * MariaDB {@code Duplicate key} 三种形态（PepperUnion #21）。
     */
    public static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        if (e.getErrorCode() == 1062) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("unique constraint") || msg.contains("duplicate entry") || msg.contains("duplicate key");
    }

    /**
     * 是否为 SQLite 繁忙（errorCode 5，或消息含 {@code sqlite_busy} / {@code database is locked}）。
     *
     * <p>这是 {@link #isTransient} 的**子集**，保留为更窄的谓词：调用方若只想识别 SQLite 单写者
     * 冲突（而不是"任何可重试的瞬时错误"），用它语义更准确。</p>
     */
    public static boolean isBusyViolation(SQLException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return e.getErrorCode() == 5 || msg.contains("sqlite_busy") || msg.contains("database is locked");
    }

    /**
     * 是否为<b>可安全重试</b>的瞬时错误——全库唯一的重试判定口径。
     *
     * <p>为什么必须只有一处：这段知识原先散在三处且各不相同——
     * {@code TransactionManager.isDeadlockOrBusy}（1213/1205/5/40001）、
     * {@link #isBusyViolation}（只有 SQLite 那几项）、
     * 以及 {@code PepperTitle} 的 {@code DbRetry}（SQLState 08/HYT + 40001）。
     * 结果是"同一个库、同一类故障，走哪条路径决定它会不会被重试"，这是最难排查的一类不一致。</p>
     *
     * <p>判定依据（按可靠性从高到低）：</p>
     * <ul>
     *   <li>{@link SQLTransientException} / {@link SQLRecoverableException}：JDBC 自己的语义；</li>
     *   <li>SQLState {@code 08xxx}（连接异常）、{@code 40001}（串行化失败）、{@code HYTxx}（超时）；</li>
     *   <li>MySQL/MariaDB 错误码 {@code 1213}（死锁）/{@code 1205}（锁等待超时）——它们的 SQLState 可能是
     *       40001 也可能缺失，所以必须同时按错误码判；</li>
     *   <li>SQLite {@link #isBusyViolation}。</li>
     * </ul>
     *
     * <p>刻意<b>不</b>把唯一键冲突（{@link #isUniqueViolation}）算作可重试：重试只会重复失败。</p>
     */
    public static boolean isTransient(SQLException e) {
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException) {
            return true;
        }
        if (e.getErrorCode() == 1213 || e.getErrorCode() == 1205) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null
                && !state.isBlank()
                && (state.startsWith("08") || "40001".equals(state) || state.startsWith("HYT"))) {
            return true;
        }
        return isBusyViolation(e);
    }
}
