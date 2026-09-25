package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * WP2（PepperUnion #18）：已应用迁移记录 checksum，重跑时校验；
 * checksum 漂移（迁移内容被改动）必须拒绝启动；旧库无 checksum 列/空值的行豁免。
 */
class MigrationChecksumTest {

    private static final class TestSqliteDialect implements SqlDialect {

        @Override
        public boolean isSqlite() {
            return true;
        }

        @Override
        public void onConnect(final Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA foreign_keys = ON");
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

    private static Migration migration(final int version, final String name, final String checksum) {
        return new Migration() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public void migrate(final Connection connection, final SqlDialect dialect) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS demo (id INT PRIMARY KEY)");
                }
            }

            // 目标 API 尚不存在（WP2 红）：先以普通方法声明，实现 Migration.checksum() 后应加 @Override。
            public String checksum() {
                return checksum;
            }
        };
    }

    @BeforeEach
    void setUp() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.connection.close();
    }

    @Test
    void freshRunRecordsChecksum() throws SQLException {
        new MigrationRunner(List.of(migration(1, "v1", "sha-abc"))).run(this.connection, this.dialect);

        try (Statement statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT checksum FROM schema_migrations WHERE version = 1")) {
            assertTrue(rs.next());
            assertEquals("sha-abc", rs.getString("checksum"));
        }
    }

    @Test
    void rerunWithSameChecksumIsClean() {
        new MigrationRunner(List.of(migration(1, "v1", "sha-abc"))).run(this.connection, this.dialect);
        new MigrationRunner(List.of(migration(1, "v1", "sha-abc"))).run(this.connection, this.dialect);
    }

    @Test
    void rerunWithChangedChecksumRefusesToRun() {
        new MigrationRunner(List.of(migration(1, "v1", "sha-abc"))).run(this.connection, this.dialect);

        final StorageException failure = assertThrows(
                StorageException.class,
                () -> new MigrationRunner(List.of(migration(1, "v1", "sha-XYZ"))).run(this.connection, this.dialect));
        assertTrue(
                failure.getMessage().contains("checksum"),
                "拒绝原因应指明 checksum 不一致，实际：" + failure.getMessage());
    }

    @Test
    void legacyTableWithoutChecksumColumnIsUpgradedAndTolerated() throws SQLException {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE schema_migrations (version INT PRIMARY KEY, name VARCHAR(255) NOT NULL,"
                            + " applied_at BIGINT NOT NULL)");
            statement.execute("INSERT INTO schema_migrations (version, name, applied_at) VALUES (1, 'v1', 0)");
        }

        // 旧库行无 checksum：豁免校验，且不重新执行已应用迁移。
        new MigrationRunner(List.of(migration(1, "v1", "sha-any"))).run(this.connection, this.dialect);

        try (Statement statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT checksum FROM schema_migrations WHERE version = 1")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString("checksum"));
        }
    }
}
