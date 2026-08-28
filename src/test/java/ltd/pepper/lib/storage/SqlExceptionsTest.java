package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SQL 异常分类：唯一键冲突 / transient 繁忙（源自 PepperBotBindManager BindManagerImpl 提取）。 */
class SqlExceptionsTest {

    @Test
    @DisplayName("真实 SQLite 唯一键冲突被识别")
    void realSqliteUniqueViolationDetected() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)");
                stmt.execute("INSERT INTO t (id, v) VALUES (1, 'a')");
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO t (id, v) VALUES (1, 'b')");
            }
        } catch (SQLException e) {
            assertTrue(SqlExceptions.isUniqueViolation(e), "SQLite 主键冲突应识别为唯一键冲突: " + e);
        }
    }

    @Test
    @DisplayName("SQLState 23xxx 系列识别为唯一键冲突")
    void sqlStateClassified() {
        SQLException sqliteState = new SQLException("UNIQUE constraint failed", "23505");
        assertTrue(SqlExceptions.isUniqueViolation(sqliteState));

        SQLException mysqlState = new SQLException("Duplicate entry '1' for key 't.PRIMARY'", "23000");
        assertTrue(SqlExceptions.isUniqueViolation(mysqlState));
    }

    @Test
    @DisplayName("消息文本兜底识别（UNIQUE constraint failed / Duplicate entry）")
    void messageFallbackClassified() {
        SQLException sqliteMsg = new SQLException("UNIQUE constraint failed: t.id");
        assertTrue(SqlExceptions.isUniqueViolation(sqliteMsg));

        SQLException mysqlMsg = new SQLException("Duplicate entry 'x' for key 't.v'");
        assertTrue(SqlExceptions.isUniqueViolation(mysqlMsg));
    }

    @Test
    @DisplayName("busy 分类：errorCode 5 / sqlite_busy / database is locked")
    void busyClassified() {
        SQLException errorCode = new SQLException("database is locked", null, 5);
        assertTrue(SqlExceptions.isBusyViolation(errorCode));

        SQLException sqliteBusy = new SQLException("SQLITE_BUSY: database is locked", null, 0);
        assertTrue(SqlExceptions.isBusyViolation(sqliteBusy));

        SQLException lockedMsg = new SQLException("SQLITE_BUSY: database is locked");
        assertTrue(SqlExceptions.isBusyViolation(lockedMsg));

        SQLException plain = new SQLException("unable to open database file");
        assertFalse(SqlExceptions.isBusyViolation(plain));
    }

    @Test
    @DisplayName("普通错误两者都不是")
    void ordinaryErrorsNotClassified() {
        SQLException syntax = new SQLException("near \"SELEC\": syntax error", "42000");
        assertFalse(SqlExceptions.isUniqueViolation(syntax));
        assertFalse(SqlExceptions.isBusyViolation(syntax));
    }
}
