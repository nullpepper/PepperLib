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
        assertFalse(SqlExceptions.isTransient(syntax), "语法错误不该被重试");
    }

    // ==================== 统一口径：isTransient ====================

    @Test
    @DisplayName("isTransient：JDBC 自带语义（SQLTransientException / SQLRecoverableException）")
    void transientByJdbcSemantics() {
        assertTrue(SqlExceptions.isTransient(new java.sql.SQLTransientException("try again")));
        assertTrue(SqlExceptions.isTransient(new java.sql.SQLRecoverableException("link down")));
    }

    @Test
    @DisplayName("isTransient：MySQL/MariaDB 死锁 1213 与锁等待超时 1205（SQLState 可能缺失）")
    void transientByMysqlErrorCodes() {
        // 关键：这两个码的 SQLState 可能是 40001，也可能缺失 —— 只按 SQLState 判会漏。
        assertTrue(SqlExceptions.isTransient(new SQLException("Deadlock found", null, 1213)));
        assertTrue(SqlExceptions.isTransient(new SQLException("Lock wait timeout exceeded", null, 1205)));
    }

    @Test
    @DisplayName("isTransient：SQLState 08xxx（连接）/ 40001（串行化）/ HYTxx（超时）")
    void transientBySqlState() {
        assertTrue(SqlExceptions.isTransient(new SQLException("communications link failure", "08S01")));
        assertTrue(SqlExceptions.isTransient(new SQLException("serialization failure", "40001")));
        assertTrue(SqlExceptions.isTransient(new SQLException("timeout", "HYT00")));
    }

    @Test
    @DisplayName("isTransient：包含 SQLite busy，但唯一键冲突与语法错误都不算（不重复失败）")
    void transientCoversBusyButNotUniqueOrSyntax() {
        assertTrue(SqlExceptions.isTransient(new SQLException("database is locked", null, 5)), "SQLite busy 必须可重试");
        assertTrue(SqlExceptions.isTransient(new SQLException("SQLITE_BUSY: database is locked")), "消息兜底形态同样算");

        assertFalse(
                SqlExceptions.isTransient(new SQLException("Duplicate entry '1' for key 't.PRIMARY'", "23000")),
                "唯一键冲突重试只会重复失败");
        assertFalse(
                SqlExceptions.isTransient(new SQLException("UNIQUE constraint failed: t.id", null, 19)),
                "SQLite 唯一约束（errorCode 19）不是 busy");
        assertFalse(SqlExceptions.isTransient(new SQLException("near \"SELEC\": syntax error", "42000")));
    }

    @Test
    @DisplayName("isBusyViolation 是 isTransient 的窄子集：SQLite busy 两者都认，死锁只有 isTransient 认")
    void busyIsNarrowerSubsetOfTransient() {
        SQLException sqliteBusy = new SQLException("database is locked", null, 5);
        assertTrue(SqlExceptions.isBusyViolation(sqliteBusy));
        assertTrue(SqlExceptions.isTransient(sqliteBusy), "窄口径命中时宽口径必须也命中");

        SQLException mysqlDeadlock = new SQLException("Deadlock found", null, 1213);
        assertFalse(SqlExceptions.isBusyViolation(mysqlDeadlock), "忙判定只针对 SQLite 单写者冲突");
        assertTrue(SqlExceptions.isTransient(mysqlDeadlock), "死锁属于可重试的瞬时错误");
    }
}
