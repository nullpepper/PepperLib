package io.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationRunnerTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        // 单连接内存库：连接关闭前数据库持续存在，事务语义与文件库一致。
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        new SqliteDialect(5000).onConnect(this.connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.connection.close();
    }

    private static Migration simpleMigration(final int version, final String name, final String ddl) {
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
                    statement.execute(ddl);
                }
            }
        };
    }

    private boolean tableExists(final String table) throws SQLException {
        return new SqliteDialect(5000).tableExists(this.connection, table);
    }

    private int appliedCount(final String table) throws SQLException {
        try (Statement statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
    }

    @Test
    void appliesMigrationsInVersionOrderAndRecordsThem() throws Exception {
        final MigrationRunner runner = new MigrationRunner(List.of(
                simpleMigration(1, "first", "CREATE TABLE t1 (id INTEGER PRIMARY KEY)"),
                simpleMigration(2, "second", "CREATE TABLE t2 (id INTEGER PRIMARY KEY)")));

        runner.run(this.connection, new SqliteDialect(5000));

        assertTrue(this.tableExists("t1"));
        assertTrue(this.tableExists("t2"));
        assertEquals(2, this.appliedCount("schema_migrations"));
    }

    @Test
    void rerunSkipsAlreadyAppliedMigrations() throws Exception {
        final MigrationRunner runner =
                new MigrationRunner(List.of(simpleMigration(1, "first", "CREATE TABLE t1 (id INTEGER PRIMARY KEY)")));

        runner.run(this.connection, new SqliteDialect(5000));
        runner.run(this.connection, new SqliteDialect(5000));

        assertEquals(1, this.appliedCount("schema_migrations"));
    }

    @Test
    void rejectsDuplicateVersions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MigrationRunner(List.of(
                        simpleMigration(1, "a", "CREATE TABLE x (id INTEGER PRIMARY KEY)"),
                        simpleMigration(1, "b", "CREATE TABLE y (id INTEGER PRIMARY KEY)"))));
    }

    @Test
    void rejectsDescendingVersions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MigrationRunner(List.of(
                        simpleMigration(2, "a", "CREATE TABLE x (id INTEGER PRIMARY KEY)"),
                        simpleMigration(1, "b", "CREATE TABLE y (id INTEGER PRIMARY KEY)"))));
    }

    @Test
    void rejectsBlankVersionTableName() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationRunner(List.of(), "  "));
    }

    @Test
    void rejectsNonIdentifierVersionTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MigrationRunner(List.of(), "bad; DROP TABLE schema_migrations"));
    }

    @Test
    void supportsCustomVersionTableName() throws Exception {
        final MigrationRunner runner = new MigrationRunner(
                List.of(simpleMigration(1, "first", "CREATE TABLE t1 (id INTEGER PRIMARY KEY)")), "pepper_versions");

        runner.run(this.connection, new SqliteDialect(5000));

        assertEquals(1, this.appliedCount("pepper_versions"));
        assertFalse(this.tableExists("schema_migrations"));
    }

    @Test
    void sqliteRollsBackMigrationAndVersionRecordOnFailure() throws Exception {
        final Migration failing = new Migration() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void migrate(final Connection connection, final SqlDialect dialect) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE t1 (id INTEGER PRIMARY KEY)");
                }
                throw new SQLException("boom");
            }
        };

        assertThrows(
                StorageException.class,
                () -> new MigrationRunner(List.of(failing)).run(this.connection, new SqliteDialect(5000)));

        assertThrows(SQLException.class, () -> this.connection.createStatement().executeQuery("SELECT * FROM t1"));
        assertEquals(0, this.appliedCount("schema_migrations"));
    }

    @Test
    void refusesStartWhenDatabaseAheadOfKnownMigrations() throws Exception {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE schema_migrations (version INT PRIMARY KEY, name VARCHAR(255), applied_at BIGINT)");
            statement.execute("INSERT INTO schema_migrations (version, name, applied_at) VALUES (99, 'unknown', 0)");
        }

        final MigrationRunner runner =
                new MigrationRunner(List.of(simpleMigration(1, "first", "CREATE TABLE t1 (id INTEGER PRIMARY KEY)")));

        final StorageException e =
                assertThrows(StorageException.class, () -> runner.run(this.connection, new SqliteDialect(5000)));
        assertTrue(e.getMessage().contains("refusing to start"));
    }

    @Test
    void validatesRequiredColumnsAfterMigration() {
        final Migration declaresColumns = new Migration() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public String name() {
                return "declares";
            }

            @Override
            public void migrate(final Connection connection, final SqlDialect dialect) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE t1 (id INTEGER PRIMARY KEY)");
                }
            }

            @Override
            public Map<String, List<String>> requiredColumns() {
                return Map.of("t1", List.of("id", "missing_column"));
            }
        };

        final StorageException e = assertThrows(
                StorageException.class,
                () -> new MigrationRunner(List.of(declaresColumns)).run(this.connection, new SqliteDialect(5000)));
        assertTrue(e.getMessage().contains("missing required column"));
    }

    @Test
    void wrapsSqlFailureInStorageException() {
        final Migration broken = new Migration() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void migrate(final Connection connection, final SqlDialect dialect) throws SQLException {
                throw new SQLException("syntax error");
            }
        };

        final StorageException e = assertThrows(
                StorageException.class,
                () -> new MigrationRunner(List.of(broken)).run(this.connection, new SqliteDialect(5000)));
        assertTrue(e.getMessage().contains("Failed to run database migrations"));
        assertTrue(e.getCause() instanceof SQLException);
    }

    // ------------------------------------------------------------------
    // rollbackAndRestore 事务安全路径（自 PepperUnion MigrationRunnerTransactionTest 迁入）
    // ------------------------------------------------------------------

    @Test
    void successfulRollbackRestoresAutoCommit() throws Exception {
        final Connection connection = mock(Connection.class);
        final SQLException failure = new SQLException("migration failed");

        assertTrue(MigrationRunner.rollbackAndRestore(connection, failure));

        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void failedRollbackClosesConnectionAndNeverRestoresAutoCommit() throws Exception {
        final Connection connection = mock(Connection.class);
        final SQLException failure = new SQLException("migration failed");
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        assertFalse(MigrationRunner.rollbackAndRestore(connection, failure));

        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
        assertTrue(failure.getSuppressed().length > 0);
    }
}
