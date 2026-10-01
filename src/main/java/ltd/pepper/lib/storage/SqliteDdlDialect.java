package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * SQLite 的 DDL 方言。
 *
 * <p>{@link #driverClass()} 留作抽象：驱动类名是 relocate 的结果，由插件绑定。</p>
 */
public abstract class SqliteDdlDialect implements DdlDialect {

    @Override
    public boolean matches(String productName) {
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("sqlite");
    }

    @Override
    public boolean isSqlite() {
        return true;
    }

    /**
     * per-connection 初始化：{@code busy_timeout} 在前（先让写冲突有界），再 WAL（提高并发读），
     * 最后 {@code foreign_keys}（SQLite 默认关闭，必须显式打开）。
     */
    @Override
    public void onConnect(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    @Override
    public String textType() {
        return "TEXT";
    }

    /**
     * SQLite 的自增主键必须是 {@code INTEGER PRIMARY KEY}——它本身就是 rowid 别名；
     * 写成 {@code BIGINT} 会失去自增语义（这正是不能靠拼字符串兼容的地方）。
     */
    @Override
    public String autoIncrementPrimaryKey() {
        return "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public boolean supportsCreateTableIfNotExists() {
        return true;
    }

    @Override
    public boolean supportsIndexIfNotExists() {
        return true;
    }

    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, tableName);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
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
