package io.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.storage.JdbcRetry.SqlFunction;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 有限次退避重试：只对 transient busy 重试，唯一键冲突等立即上抛（源自 BindManagerImpl 提取）。 */
class JdbcRetryTest {

    @Test
    @DisplayName("busy 前两次失败后成功，共尝试 3 次并返回结果")
    void retriesBusyThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger connectionsOpened = new AtomicInteger();
        String result = JdbcRetry.withConnectionRetry(
                () -> {
                    connectionsOpened.incrementAndGet();
                    return null;
                },
                conn -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new SQLException("SQLITE_BUSY: database is locked", null, 5);
                    }
                    return "ok";
                },
                3,
                1);

        assertEquals("ok", result);
        assertEquals(3, attempts.get(), "应重试到第 3 次成功");
        assertEquals(3, connectionsOpened.get(), "每次尝试应重新获取连接");
    }

    @Test
    @DisplayName("非 busy 异常（唯一键冲突）立即上抛，不重试")
    void rethrowsNonBusyImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException thrown = assertThrows(
                SQLException.class,
                () -> JdbcRetry.withConnectionRetry(
                        () -> null,
                        conn -> {
                            attempts.incrementAndGet();
                            throw new SQLException("UNIQUE constraint failed: t.id", "23505");
                        },
                        3,
                        1));
        assertEquals("UNIQUE constraint failed: t.id", thrown.getMessage());
        assertEquals(1, attempts.get(), "唯一键冲突不应重试");
    }

    @Test
    @DisplayName("持续 busy 重试耗尽后抛出最后一次异常")
    void exhaustsRetries() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException thrown = assertThrows(
                SQLException.class,
                () -> JdbcRetry.withConnectionRetry(
                        () -> null,
                        conn -> {
                            attempts.incrementAndGet();
                            throw new SQLException("database is locked", null, 5);
                        },
                        3,
                        1));
        assertEquals("database is locked", thrown.getMessage());
        assertEquals(3, attempts.get(), "达到最大重试次数后不再继续");
    }

    @Test
    @DisplayName("默认参数与自定义重试次数等价路径")
    void defaultAndCustomBothWork() throws Exception {
        // 默认参数（3 次 / 50ms）：一次成功
        assertEquals("once", JdbcRetry.withConnectionRetry(() -> null, conn -> "once"));

        // 自定义 5 次：busy 4 次后成功
        AtomicInteger attempts = new AtomicInteger();
        String result = JdbcRetry.withConnectionRetry(
                () -> null,
                conn -> {
                    if (attempts.incrementAndGet() < 5) {
                        throw new SQLException("SQLITE_BUSY", null, 5);
                    }
                    return "five";
                },
                5,
                1);
        assertEquals("five", result);
        assertEquals(5, attempts.get());
    }

    @Test
    @DisplayName("SqlFunction 是带检查异常的 SAM，可抛 SQLException")
    void sqlFunctionSamCompiles() {
        SqlFunction<String> fn = conn -> {
            throw new SQLException("synthetic");
        };
        assertTrue(fn != null);
        assertThrows(SQLException.class, () -> fn.apply(null));
    }
}
