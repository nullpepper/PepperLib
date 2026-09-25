package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP3（PepperUnion #12-2）：补偿 outbox——入队、认领、完成，
 * 以及启动自愈：滞留 PROCESSING 的条目超龄后重置回 PENDING 重放，不再卡死等人工。
 */
class CompensationOutboxTest {

    private static final class TestSqliteDialect implements SqlDialect {
        @Override
        public boolean isSqlite() {
            return true;
        }

        @Override
        public void onConnect(final Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
            }
        }

        @Override
        public boolean tableExists(final Connection connection, final String tableName) throws SQLException {
            try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
                return rs.next();
            }
        }

        @Override
        public Class<? extends java.sql.Driver> driverClass() {
            return org.sqlite.JDBC.class;
        }
    }

    private Connection connection;
    private final SqlDialect dialect = new TestSqliteDialect();
    private CompensationOutbox outbox;

    @BeforeEach
    void setUp() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        this.outbox = new CompensationOutbox("comp_outbox");
        this.outbox.ensureTable(this.connection, this.dialect);
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.connection.close();
    }

    @Test
    void enqueueClaimCompleteFlow() throws SQLException {
        final long id = this.outbox.enqueue(this.connection, "guild-refund", "{\"amount\":1}");
        final List<CompensationOutbox.Entry> claimed = this.outbox.claimPending(this.connection, 10);
        assertEquals(1, claimed.size());
        assertEquals(id, claimed.get(0).id());
        assertEquals("guild-refund", claimed.get(0).kind());

        this.outbox.markCompleted(this.connection, id);
        assertTrue(this.outbox.claimPending(this.connection, 10).isEmpty(), "已完成条目不得重复认领");
    }

    @Test
    void claimPendingDoesNotReclaimProcessing() throws SQLException {
        this.outbox.enqueue(this.connection, "k", "p");
        assertEquals(1, this.outbox.claimPending(this.connection, 10).size());
        assertTrue(this.outbox.claimPending(this.connection, 10).isEmpty(), "PROCESSING 条目不得被重复认领");
    }

    @Test
    void staleProcessingIsResetToPendingForReplay() throws SQLException {
        // 入队并认领，然后把更新时间伪造为 1 小时前（模拟崩溃后滞留）。
        this.outbox.enqueue(this.connection, "k", "p");
        assertEquals(1, this.outbox.claimPending(this.connection, 10).size());
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("UPDATE comp_outbox SET updated_at = updated_at - 3600000");
        }

        final int reset = this.outbox.resetStaleProcessing(this.connection, 60_000);
        assertEquals(1, reset, "超龄 PROCESSING 应被自愈重置");
        assertEquals(1, this.outbox.claimPending(this.connection, 10).size(), "重置后应可重新认领重放");
    }

    @Test
    void freshProcessingIsNotReset() throws SQLException {
        this.outbox.enqueue(this.connection, "k", "p");
        this.outbox.claimPending(this.connection, 10);

        assertEquals(0, this.outbox.resetStaleProcessing(this.connection, 60_000));
    }
}
