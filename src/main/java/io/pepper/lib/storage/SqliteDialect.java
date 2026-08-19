package io.pepper.lib.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite 方言：PRAGMA 初始化序列与两插件现有实现逐字一致。 */
public final class SqliteDialect implements SqlDialect {

    private final int busyTimeoutMs;

    public SqliteDialect(final int busyTimeoutMs) {
        if (busyTimeoutMs < 0) {
            throw new IllegalArgumentException("busyTimeoutMs must be >= 0, got " + busyTimeoutMs);
        }
        this.busyTimeoutMs = busyTimeoutMs;
    }

    @Override
    public boolean isSqlite() {
        return true;
    }

    @Override
    public Class<? extends Driver> driverClass() {
        return org.sqlite.JDBC.class;
    }

    @Override
    public void onConnect(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // 先设 busy_timeout 再切 WAL：首次从 DELETE 切 WAL 需要拿独占锁，
            // 若 busy_timeout 还没生效，其它连接正在读写时切换会直接失败。
            statement.execute("PRAGMA busy_timeout = " + this.busyTimeoutMs);
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    @Override
    public boolean tableExists(final Connection connection, final String tableName) throws SQLException {
        final DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[] {"TABLE"})) {
            return rs.next();
        }
    }
}
