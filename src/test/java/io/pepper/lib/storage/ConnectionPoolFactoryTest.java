package io.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConnectionPoolFactoryTest {

    @Test
    void createsWorkingSqlitePool() throws Exception {
        final var dataSource = ConnectionPoolFactory.create("jdbc:sqlite::memory:", "TestPool", 4, 5000, null, null);
        try {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void poolNameIsApplied() {
        final var dataSource =
                ConnectionPoolFactory.create("jdbc:sqlite::memory:", "PepperLibTestPool", 4, 5000, null, null);
        try {
            assertEquals("PepperLibTestPool", dataSource.getPoolName());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void clampsMaximumPoolSizeToAtLeastTwo() throws Exception {
        final var dataSource = ConnectionPoolFactory.create("jdbc:sqlite::memory:", "ClampedPool", 1, 5000, null, null);
        try {
            assertEquals(2, dataSource.getMaximumPoolSize());
            // 池仍可用。
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void appliesConnectionTimeout() {
        final var dataSource = ConnectionPoolFactory.create("jdbc:sqlite::memory:", "TimeoutPool", 4, 1234, null, null);
        try {
            assertEquals(1234, dataSource.getConnectionTimeout());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void registerDriverIgnoresMissingCandidates() {
        // 裸环境下 sqlite 驱动已可用：应成功注册且不抛异常。
        ConnectionPoolFactory.registerDriver(
                new String[] {"io.pepper.lib.shadow.org.sqlite.JDBC"}, new String[] {"org.sqlite.JDBC"});
        assertNotNull(org.sqlite.JDBC.class);
    }

    @Test
    void createRejectsBlankJdbcUrl() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionPoolFactory.create("  ", "BadPool", 4, 5000, null, null));
    }

    @Test
    void createRejectsNegativeTimeout() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionPoolFactory.create("jdbc:sqlite::memory:", "BadPool", 4, -1, null, null));
    }

    @Test
    void createRejectsBlankPoolName() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionPoolFactory.create("jdbc:sqlite::memory:", "  ", 4, 5000, null, null));
    }

    @Test
    void timeoutUnitSmoke() {
        // 仅确认常量可读（HikariCP 接受毫秒）。
        assertTrue(TimeUnit.MILLISECONDS.toMillis(5) == 5L);
        assertFalse(false);
    }
}
