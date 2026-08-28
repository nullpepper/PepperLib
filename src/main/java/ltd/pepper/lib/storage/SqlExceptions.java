package ltd.pepper.lib.storage;

import java.sql.SQLException;
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
     * 是否为唯一键/主键冲突（SQLState 23xxx，或消息含
     * {@code UNIQUE constraint failed} / {@code Duplicate entry}）。
     */
    public static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String msg = String.valueOf(e.getMessage());
        return msg.contains("UNIQUE constraint failed") || msg.contains("Duplicate entry");
    }

    /**
     * 是否为 transient 繁忙（SQLite errorCode 5，或消息含
     * {@code sqlite_busy} / {@code database is locked}）——重试安全的唯一类型。
     */
    public static boolean isBusyViolation(SQLException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return e.getErrorCode() == 5 || msg.contains("sqlite_busy") || msg.contains("database is locked");
    }
}
