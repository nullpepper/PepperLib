package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * MariaDB / MySQL 的 DDL 方言（兼容两者）。
 *
 * <p>{@link #driverClass()} 留作抽象：驱动类名是 relocate 的结果，只能由被 shade 的插件提供
 * ——插件用一个薄包装继承本类并绑定字面量即可，DDL 部分无需再写。</p>
 */
public abstract class MariaDbDdlDialect implements DdlDialect {

    @Override
    public boolean matches(String productName) {
        if (productName == null) {
            return false;
        }
        String normalized = productName.toLowerCase(Locale.ROOT);
        return normalized.contains("mysql") || normalized.contains("mariadb");
    }

    @Override
    public boolean isSqlite() {
        return false;
    }

    @Override
    public void onConnect(Connection connection) {
        // MariaDB 无需 per-connection 初始化（SQLite 的 PRAGMA 在 SqliteDdlDialect.onConnect）。
    }

    @Override
    public String textType() {
        return "LONGTEXT";
    }

    @Override
    public String autoIncrementPrimaryKey() {
        return "id BIGINT PRIMARY KEY AUTO_INCREMENT";
    }

    @Override
    public boolean supportsCreateTableIfNotExists() {
        return true;
    }

    @Override
    public boolean supportsIndexIfNotExists() {
        return false; // MySQL/MariaDB 不支持 CREATE INDEX IF NOT EXISTS
    }

    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var rs = connection.getMetaData().getTables(null, null, tableName, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public boolean columnExists(Connection connection, String tableName, String column) {
        return DialectSupport.columnExists(connection, tableName, column);
    }

    @Override
    public boolean addColumnIfMissing(Connection connection, String tableName, String column, String columnDdl)
            throws SQLException {
        return DialectSupport.addColumnIfMissing(connection, tableName, column, columnDdl);
    }

    @Override
    public long insertReturningKey(Connection connection, String sql, StatementBinder binder) throws SQLException {
        return DialectSupport.insertReturningKey(connection, sql, binder);
    }
}
