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

    /**
     * {@code CREATE INDEX IF NOT EXISTS} 的支持情况<b>在同一方言类内并不一致</b>：MariaDB 自 10.1.4 起
     * <b>支持</b>该语法（2026-10-01 在 MariaDB 11.8 实测通过；生产 PepperTitle 的 {@code idx_grants_lookup}
     * 正是用它建成的），而 <b>MySQL 不支持</b>。本类同时服务 {@code MYSQL} 与 {@code MARIADB} 两种
     * storage.type，因此<b>保守返回 {@code false}</b>——调用方走"普通 {@code CREATE INDEX} + 幂等处理"
     * 这条路在两者上都成立。
     *
     * <p>（此前注释写作"MySQL/MariaDB 不支持"，那是把 MySQL 的行为当成了两者的行为。）若某部署确定只跑
     * MariaDB，可覆盖为 {@code true}。做成常量而非按连接探测，是因为本接口无 {@code Connection} 参数，
     * 无法按服务端产品名判定。</p>
     */
    @Override
    public boolean supportsIndexIfNotExists() {
        return false;
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
