package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 泛化补偿 outbox（源自 PepperUnion {@code CompensationOutbox}，PepperUnion #12-2 自愈）：
 * 不可撤销的资金操作先入队（PENDING），执行时认领（PROCESSING），完成后置 COMPLETED；
 * 崩溃后滞留 PROCESSING 的条目由 {@link #resetStaleProcessing} 超龄自愈重置回 PENDING
 * 重放，不再「卡死等人工」。
 */
public final class CompensationOutbox {

    /** 一条补偿记录。 */
    public record Entry(long id, String kind, String payload, String status) {}

    private final String tableName;

    /**
     * @param tableName outbox 表名（仅字母数字下划线）
     */
    public CompensationOutbox(final String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("tableName must match [A-Za-z0-9_]+");
        }
        this.tableName = tableName;
    }

    /** 建表（幂等）。 */
    public void ensureTable(final Connection connection, final SqlDialect dialect) throws SQLException {
        final String idColumn = dialect.isSqlite()
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT PRIMARY KEY AUTO_INCREMENT";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + this.tableName + " ("
                    + idColumn + ", "
                    + "kind VARCHAR(64) NOT NULL, "
                    + "payload VARCHAR(4096) NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL)");
        }
    }

    /** 入队一条补偿（PENDING）。 */
    public long enqueue(final Connection connection, final String kind, final String payload) throws SQLException {
        final long now = System.currentTimeMillis();
        final String sql = "INSERT INTO " + this.tableName
                + " (kind, payload, status, created_at, updated_at) VALUES (?, ?, 'PENDING', ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, payload);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("enqueue did not return a generated id");
    }

    /** 认领至多 {@code limit} 条 PENDING（置 PROCESSING 并返回）。 */
    public List<Entry> claimPending(final Connection connection, final int limit) throws SQLException {
        final List<Entry> claimed = new ArrayList<>();
        final String select = "SELECT id, kind, payload, status FROM " + this.tableName
                + " WHERE status = 'PENDING' ORDER BY id LIMIT " + Math.max(0, limit);
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(select)) {
            while (rs.next()) {
                claimed.add(new Entry(rs.getLong("id"), rs.getString("kind"), rs.getString("payload"), "PROCESSING"));
            }
        }
        final String update = "UPDATE " + this.tableName
                + " SET status = 'PROCESSING', updated_at = ? WHERE id = ? AND status = 'PENDING'";
        for (final Entry entry : claimed) {
            try (PreparedStatement ps = connection.prepareStatement(update)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, entry.id());
                ps.executeUpdate();
            }
        }
        return claimed;
    }

    /** 完成一条补偿（置 COMPLETED）。 */
    public void markCompleted(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + this.tableName + " SET status = 'COMPLETED', updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * 启动自愈：把滞留 PROCESSING 超过 {@code olderThanMillis} 的条目重置回 PENDING
     * （视为崩溃遗留，允许重放）。返回重置条数。
     */
    public int resetStaleProcessing(final Connection connection, final long olderThanMillis) throws SQLException {
        final long cutoff = System.currentTimeMillis() - Math.max(0, olderThanMillis);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + this.tableName + " SET status = 'PENDING', updated_at = ?"
                        + " WHERE status = 'PROCESSING' AND updated_at <= ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, cutoff);
            return ps.executeUpdate();
        }
    }
}
