package io.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDialectTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        this.dbFile = this.tempDir.resolve("test.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.connection.close();
        Files.deleteIfExists(this.dbFile);
    }

    @Test
    void reportsSqlite() {
        assertTrue(new SqliteDialect(5000).isSqlite());
        assertFalse(new SqliteDialect(5000).isSqlite() == false);
    }

    @Test
    void resolvesSqliteDriverClass() {
        assertEquals(org.sqlite.JDBC.class, new SqliteDialect(5000).driverClass());
    }

    @Test
    void onConnectSetsBusyTimeoutJournalModeAndForeignKeys() throws Exception {
        new SqliteDialect(1234).onConnect(this.connection);

        try (Statement statement = this.connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rs.next());
                assertEquals(1234, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1));
            }
            try (ResultSet rs = statement.executeQuery("PRAGMA foreign_keys")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void tableExistsReflectsActualTables() throws Exception {
        final SqliteDialect dialect = new SqliteDialect(5000);
        assertFalse(dialect.tableExists(this.connection, "some_table"));

        try (Statement statement = this.connection.createStatement()) {
            statement.execute("CREATE TABLE some_table (id INTEGER PRIMARY KEY)");
        }

        assertTrue(dialect.tableExists(this.connection, "some_table"));
    }
}
