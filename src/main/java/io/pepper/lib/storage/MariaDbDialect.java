package io.pepper.lib.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL 家族方言。使用 MariaDB Connector/J（{@code org.mariadb.jdbc.Driver}）：
 * 该驱动同时兼容 MySQL 与 MariaDB 协议。
 */
public final class MariaDbDialect implements SqlDialect {

    @Override
    public boolean isSqlite() {
        return false;
    }

    @Override
    public Class<? extends Driver> driverClass() {
        return org.mariadb.jdbc.Driver.class;
    }

    @Override
    public void onConnect(final Connection connection) {
        // MySQL/MariaDB 无需 per-connection pragma。
    }

    @Override
    public boolean tableExists(final Connection connection, final String tableName) throws SQLException {
        final DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[] {"TABLE"})) {
            return rs.next();
        }
    }
}
