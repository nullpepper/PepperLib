package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 金融级事务发件箱与补偿 Outbox 引擎（强化版）。
 *
 * <p>特性保证：</p>
 * <ul>
 *   <li><b>防刷保护</b>：超龄滞留自愈增加最大尝试上限（默认 5 次），超过上限自动流转至
 *       {@code MANUAL_REVIEW} 状态，彻底封死因无限重放导致的代币凭空刷取漏洞；</li>
 *   <li><b>严格 CAS 租约认领</b>：认领时校验受影响行数并支持 {@code lock_token}，
 *       杜绝多节点/多线程并发认领同一个任务；</li>
 *   <li><b>确定性状态流转</b>：{@code PENDING -> PROCESSING -> COMPLETED / MANUAL_REVIEW}。</li>
 * </ul>
 */
public final class CompensationOutbox {

    /** 一条补偿记录。 */
    public record Entry(long id, String kind, String payload, String status, String lockToken, int attempts) {
        public Entry(long id, String kind, String payload, String status) {
            this(id, kind, payload, status, null, 0);
        }
    }

    private final String tableName;
    private final int defaultMaxAttempts;

    /**
     * @param tableName outbox 表名（仅字母数字下划线）
     */
    public CompensationOutbox(final String tableName) {
        this(tableName, 5);
    }

    public CompensationOutbox(final String tableName, final int defaultMaxAttempts) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("tableName must match [A-Za-z0-9_]+");
        }
        this.tableName = tableName;
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
    }

    /** 建表（幂等）。 */
    public void ensureTable(final Connection connection, final SqlDialect dialect) throws SQLException {
        final String idColumn =
                dialect.isSqlite() ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : "id BIGINT PRIMARY KEY AUTO_INCREMENT";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + this.tableName + " ("
                    + idColumn + ", "
                    + "kind VARCHAR(64) NOT NULL, "
                    + "payload VARCHAR(4096) NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, "
                    + "lock_token VARCHAR(64) NULL, "
                    + "attempts INT NOT NULL DEFAULT 0, "
                    + "max_attempts INT NOT NULL DEFAULT " + this.defaultMaxAttempts + ", "
                    + "created_at BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL)");
        }
        ensureColumn(connection, "lock_token", "VARCHAR(64) NULL");
        ensureColumn(connection, "attempts", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "max_attempts", "INT NOT NULL DEFAULT " + this.defaultMaxAttempts);
    }

    private void ensureColumn(final Connection connection, final String column, final String ddl) {
        try (Statement statement = connection.createStatement()) {
            statement
                    .executeQuery("SELECT " + column + " FROM " + this.tableName + " WHERE 1=0")
                    .close();
        } catch (final SQLException notFound) {
            try (Statement alter = connection.createStatement()) {
                alter.execute("ALTER TABLE " + this.tableName + " ADD COLUMN " + column + " " + ddl);
            } catch (final SQLException ignored) {
            }
        }
    }

    /** 入队一条补偿（PENDING）。 */
    public long enqueue(final Connection connection, final String kind, final String payload) throws SQLException {
        final long now = System.currentTimeMillis();
        final String sql = "INSERT INTO " + this.tableName
                + " (kind, payload, status, attempts, max_attempts, created_at, updated_at) "
                + "VALUES (?, ?, 'PENDING', 0, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, payload);
            ps.setInt(3, this.defaultMaxAttempts);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("enqueue did not return a generated id");
    }

    /** 认领至多 {@code limit} 条 PENDING（置 PROCESSING 并返回）。兼容旧接口。 */
    public List<Entry> claimPending(final Connection connection, final int limit) throws SQLException {
        return claimPending(connection, limit, UUID.randomUUID().toString());
    }

    /** 带唯一租约 Token 的原子认领。只有抢占成功的条目才会被返回。 */
    public List<Entry> claimPending(final Connection connection, final int limit, final String lockToken)
            throws SQLException {
        final List<Entry> candidates = new ArrayList<>();
        final String select = "SELECT id, kind, payload, status, attempts FROM " + this.tableName
                + " WHERE status = 'PENDING' ORDER BY id LIMIT " + Math.max(0, limit);
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(select)) {
            while (rs.next()) {
                candidates.add(new Entry(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("payload"),
                        "PROCESSING",
                        lockToken,
                        rs.getInt("attempts") + 1));
            }
        }

        final List<Entry> claimed = new ArrayList<>();
        final String update = "UPDATE " + this.tableName
                + " SET status = 'PROCESSING', lock_token = ?, attempts = attempts + 1, updated_at = ? "
                + "WHERE id = ? AND status = 'PENDING'";
        for (final Entry entry : candidates) {
            try (PreparedStatement ps = connection.prepareStatement(update)) {
                ps.setString(1, lockToken);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, entry.id());
                // 原子 CAS 校验：必须恰好更新 1 行，杜绝多实例并发抢占相同记录
                if (ps.executeUpdate() == 1) {
                    claimed.add(entry);
                }
            }
        }
        return claimed;
    }

    /** 完成一条补偿（置 COMPLETED 并清空租约）。 */
    public void markCompleted(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE " + this.tableName
                + " SET status = 'COMPLETED', lock_token = NULL, updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 标记任务失败并释放租约（若未超过上限置 PENDING，超限置 MANUAL_REVIEW）。 */
    public void markFailed(final Connection connection, final long id) throws SQLException {
        final String sql = "UPDATE " + this.tableName
                + " SET status = CASE WHEN attempts >= max_attempts THEN 'MANUAL_REVIEW' ELSE 'PENDING' END, "
                + "lock_token = NULL, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * 启动自愈：把滞留 PROCESSING 超过 {@code olderThanMillis} 的条目安全重置。
     *
     * <p>防刷关键：未超限重置为 PENDING，超限重置为 MANUAL_REVIEW 冻结等待人工审查，杜绝无限复制。</p>
     */
    public int resetStaleProcessing(final Connection connection, final long olderThanMillis) throws SQLException {
        final long cutoff = System.currentTimeMillis() - Math.max(0, olderThanMillis);
        final String sql = "UPDATE " + this.tableName
                + " SET status = CASE WHEN attempts >= max_attempts THEN 'MANUAL_REVIEW' ELSE 'PENDING' END, "
                + "lock_token = NULL, updated_at = ? "
                + "WHERE status = 'PROCESSING' AND updated_at <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, cutoff);
            return ps.executeUpdate();
        }
    }
}
