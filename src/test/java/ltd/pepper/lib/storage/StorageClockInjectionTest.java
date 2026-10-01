package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存储层时间来源可注入：写进库的时间戳必须来自注入的时钟，而不是 {@code System.currentTimeMillis()}。
 *
 * <p>为什么值得单独钉住：{@link CompensationOutbox} 的核心语义是<b>时间相关</b>的——"滞留多久"
 * 决定一条补偿是重置为 PENDING 还是冻结成 MANUAL_REVIEW（防无限重放的防刷闸门）。时钟写死时，
 * 这类判定只能靠 {@code Thread.sleep} 间接测，而 sleep 测试天生不稳定、也没法断言边界。</p>
 *
 * <p>断言方式刻意选"读回库里的真实值"，而不是"看代码里有没有调 System"——
 * 后者是静态检查，前者才是行为证据。</p>
 */
class StorageClockInjectionTest {

    private static final class TestSqliteDialect implements SqlDialect {
        @Override
        public boolean isSqlite() {
            return true;
        }

        @Override
        public void onConnect(final Connection connection) throws SQLException {}

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

    private static final Instant FIXED_NOW = Instant.parse("2026-10-01T12:00:00Z");

    private Connection connection;
    private final SqlDialect dialect = new TestSqliteDialect();

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    /** 读回某条记录的 created_at / updated_at（真值来自库，不经被测代码的报告）。 */
    private long timestampOf(long id, String column) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT " + column + " FROM comp_outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "记录不存在：" + id);
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("enqueue 写入的时间戳来自注入的时钟（不是系统时钟）")
    void enqueueUsesInjectedClock() throws Exception {
        CompensationOutbox outbox = new CompensationOutbox("comp_outbox", 5, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        outbox.ensureTable(connection, dialect);

        long id = outbox.enqueue(connection, "REFUND", "payload");

        assertEquals(FIXED_NOW.toEpochMilli(), timestampOf(id, "created_at"), "created_at 应来自注入时钟");
        assertEquals(FIXED_NOW.toEpochMilli(), timestampOf(id, "updated_at"), "updated_at 应来自注入时钟");
    }

    @Test
    @DisplayName("时钟前移会被如实写入：更新类操作的时间戳随时间推进")
    void updateTimestampsFollowTheClock() throws Exception {
        // 用可变时钟：先固定 T，再推进到 T+1h，验证 updated_at 跟着走。
        java.time.Clock[] holder = {Clock.fixed(FIXED_NOW, ZoneOffset.UTC)};
        Clock movable = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return holder[0].instant();
            }
        };
        CompensationOutbox outbox = new CompensationOutbox("comp_outbox", 5, movable);
        outbox.ensureTable(connection, dialect);
        long id = outbox.enqueue(connection, "REFUND", "p");
        assertEquals(FIXED_NOW.toEpochMilli(), timestampOf(id, "updated_at"));

        Instant later = FIXED_NOW.plusSeconds(3600);
        holder[0] = Clock.fixed(later, ZoneOffset.UTC);
        outbox.markCompleted(connection, id);

        assertEquals(later.toEpochMilli(), timestampOf(id, "updated_at"), "updated_at 应随注入时钟前移");
        assertEquals(FIXED_NOW.toEpochMilli(), timestampOf(id, "created_at"), "created_at 不该被改写");
    }

    @Test
    @DisplayName("resetStaleProcessing 的\"滞留多久\"完全由注入时钟决定（无需 sleep）")
    void staleResetIsDrivenByInjectedClock() throws Exception {
        // 库内时间戳固定在 T；当前时钟设为 T+10min，阈值 5min ⇒ 该条应被判为滞留。
        CompensationOutbox writer = new CompensationOutbox("comp_outbox", 5, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        writer.ensureTable(connection, dialect);
        long id = writer.enqueue(connection, "REFUND", "p");
        writer.claimPending(connection, 10);

        CompensationOutbox reader =
                new CompensationOutbox("comp_outbox", 5, Clock.fixed(FIXED_NOW.plusSeconds(600), ZoneOffset.UTC));
        int reset = reader.resetStaleProcessing(connection, 300_000L);

        assertEquals(1, reset, "超过 5 分钟未更新的 PROCESSING 记录应被重置");
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT status, lock_token FROM comp_outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("status"), "未超上限应回到 PENDING 等待重试");
            }
        }
    }

    @Test
    @DisplayName("未达滞留阈值时不动它（阈值判定同样来自注入时钟，不是真实等待）")
    void freshProcessingIsLeftAlone() throws Exception {
        CompensationOutbox writer = new CompensationOutbox("comp_outbox", 5, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        writer.ensureTable(connection, dialect);
        writer.enqueue(connection, "REFUND", "p");
        writer.claimPending(connection, 10);

        // 只过了 1 分钟，阈值 5 分钟 ⇒ 不该重置。
        CompensationOutbox reader =
                new CompensationOutbox("comp_outbox", 5, Clock.fixed(FIXED_NOW.plusSeconds(60), ZoneOffset.UTC));

        assertEquals(0, reader.resetStaleProcessing(connection, 300_000L));
    }

    @Test
    @DisplayName("MigrationRunner 的 applied_at 来自注入时钟")
    void migrationAppliedAtUsesInjectedClock() throws Exception {
        Migration migration = new Migration() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public String name() {
                return "first";
            }

            @Override
            public void migrate(Connection connection, SqlDialect dialect) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS t1 (id INTEGER PRIMARY KEY)");
                }
            }
        };
        MigrationRunner runner = new MigrationRunner(
                List.of(migration),
                "schema_migrations",
                null,
                java.util.Set.<Integer>of(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        runner.run(connection, dialect);

        try (PreparedStatement ps =
                        connection.prepareStatement("SELECT applied_at FROM schema_migrations WHERE version = 1");
                ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "迁移应已记录");
            assertEquals(FIXED_NOW.toEpochMilli(), rs.getLong(1), "applied_at 应来自注入时钟");
        }
    }
}
