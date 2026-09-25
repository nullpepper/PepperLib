package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * WP2（PepperUnion #21）：唯一键冲突判定必须先看错误码/SQLState，
 * 文案兜底需大小写不敏感并覆盖 MariaDB 的 {@code Duplicate key} 形态。
 */
class SqlExceptionsErrorCodeTest {

    @Test
    void mysqlErrorCode1062WithNullStateIsUniqueViolation() {
        assertTrue(
                SqlExceptions.isUniqueViolation(new SQLException("dup", null, 1062)),
                "errorCode 1062 应判唯一键冲突（SQLState 为 null 时也要命中）");
    }

    @Test
    void duplicateKeyMessageIsMatchedCaseInsensitively() {
        assertTrue(
                SqlExceptions.isUniqueViolation(
                        new SQLException("Duplicate key value violates unique constraint", null, 0)),
                "MariaDB 的 Duplicate key 文案应命中");
    }

    @Test
    void lowercaseUniqueConstraintMessageIsMatched() {
        assertTrue(
                SqlExceptions.isUniqueViolation(
                        new SQLException("unique constraint failed: t.a".toLowerCase(), null, 0)),
                "SQLite 小写文案应命中");
    }

    @Test
    void notNullConstraintIsNotUniqueViolation() {
        assertFalse(
                SqlExceptions.isUniqueViolation(new SQLException("NOT NULL constraint failed: t.a", null, 0)),
                "非唯一键约束失败不得误判");
    }
}
