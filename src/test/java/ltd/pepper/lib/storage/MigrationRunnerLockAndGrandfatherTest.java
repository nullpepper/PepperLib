package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationRunnerLockAndGrandfatherTest {

    private Connection connection;

    private static final class TestSqliteDialect implements SqlDialect {
        @Override
        public boolean isSqlite() {
            return true;
        }

        @Override
        public void onConnect(final Connection connection) throws SQLException {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
            }
        }

        @Override
        public boolean tableExists(final Connection connection, final String tableName) {
            return false;
        }

        @Override
        public Class<? extends java.sql.Driver> driverClass() {
            return org.sqlite.JDBC.class;
        }
    }

    private final SqlDialect dialect = new TestSqliteDialect();

    @BeforeEach
    void setUp() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.connection.close();
    }

    @Test
    void grandfatherVersionsExemptsHistoricalVersionsFromRejection() throws SQLException {
        // 模拟旧库已应用版本 1、2、3
        try (Statement s = this.connection.createStatement()) {
            s.execute(
                    "CREATE TABLE schema_migrations (version INT PRIMARY KEY, name VARCHAR(255), applied_at BIGINT, checksum VARCHAR(255))");
            s.execute(
                    "INSERT INTO schema_migrations VALUES (1, 'v1', 100, NULL), (2, 'v2', 200, NULL), (3, 'v3', 300, NULL)");
        }

        final Migration m1 = new Migration() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public String name() {
                return "v1";
            }

            @Override
            public void migrate(Connection c, SqlDialect d) {}
        };

        // 此时代码库重构折叠，只剩 m1，但声明 grandfatherVersions 包含 2 和 3
        final MigrationRunner runnerWithGrandfather =
                new MigrationRunner(List.of(m1), "schema_migrations", null, Set.of(2, 3));

        // 不应抛出未知版本异常拒绝启动
        assertDoesNotThrow(() -> runnerWithGrandfather.run(this.connection, this.dialect));
    }
}
