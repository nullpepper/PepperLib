package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 方言实现共享的 JDBC 细节：列存在性探测、幂等补列、生成键取回。
 *
 * <p>这些逻辑在 MariaDB 与 SQLite 上<b>写法相同</b>（差异只在类型名与主键片段），因此收在一处：
 * 各写一遍就会出现"某个方言吞掉 ALTER 失败、另一个上抛"这类静默分歧。</p>
 */
final class DialectSupport {

    private DialectSupport() {}

    /**
     * 直接探测列是否存在：{@code SELECT <col> FROM <t> WHERE 1=0}。
     *
     * <p>刻意不用 {@code DatabaseMetaData.getColumns}：MariaDB 下
     * {@code getColumns(null, null, table, null)} 会跨库匹配同名表，造成"列已存在"的假阳性
     * （PepperLib 自身在生产回归中踩过一次，见 {@link MigrationRunner#ensureTable}）。</p>
     */
    static boolean columnExists(Connection connection, String tableName, String column) {
        try (Statement statement = connection.createStatement()) {
            statement
                    .executeQuery("SELECT " + column + " FROM " + tableName + " WHERE 1=0")
                    .close();
            return true;
        } catch (SQLException notFound) {
            return false;
        }
    }

    /**
     * 缺列才 ALTER；已经存在时不做任何事。
     *
     * <p>ALTER 失败<b>不吞</b>：权限不足 / 锁等待超时 / 语法错误都会上抛。吞掉它会让 schema
     * 半迁移且没有告警，直到后续 SELECT 才报"未知列"，那时已经很难归因。</p>
     */
    static boolean addColumnIfMissing(Connection connection, String tableName, String column, String columnDdl)
            throws SQLException {
        if (columnExists(connection, tableName, column)) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + column + " " + columnDdl);
        }
        return true;
    }

    /**
     * {@code INSERT} + {@code RETURN_GENERATED_KEYS}，取回第一列作为主键。
     *
     * <p>不掩饰失败：驱动没给键就抛，而不是返回 0 或 -1——那会让调用方拿到一个"看起来像主键"
     * 的假值。</p>
     */
    static long insertReturningKey(Connection connection, String sql, DdlDialect.StatementBinder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.bind(statement);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("INSERT 未取回生成键（驱动未返回 generated keys）：" + sql);
    }
}
