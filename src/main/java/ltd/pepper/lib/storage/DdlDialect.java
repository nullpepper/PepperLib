package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 方言的 <b>DDL 生成能力</b>：把「同一张表在 MariaDB 与 SQLite 上的写法差异」收成一处。
 *
 * <p><b>为什么单独一层，而不是塞进 {@link SqlDialect}</b>：两者面对不同的实现者。
 * {@code SqlDialect} 最小核心要求实现者给出 {@code driverClass()}——而驱动类名是
 * <b>relocate 的结果，每个插件都不同</b>（实测有三种形态：某插件把它重定位到自己的命名空间下、
 * 另一个重定位到不同的子包、还有一个直接用未重定位的驱动），
 * 因此实现只能落在被 shade 的插件侧。DDL 生成本身<b>与驱动无关</b>，可以完整地住在库里。</p>
 *
 * <p><b>什么该收、什么不该收</b>（依据 Claim 与 Union 现有方言的实际内容）：</p>
 * <ul>
 *   <li><b>收</b>：类型映射、自增主键片段、幂等建表的可用性、索引是否支持 {@code IF NOT EXISTS}、
 *       列存在性探测、生成键取回、产品名匹配——这些是"同一语义、两种写法"。</li>
 *   <li><b>不收</b>：带业务表名的 SQL（例如 {@code INSERT IGNORE INTO pepperclaim_player_quota…}）。
 *       那是插件的领域知识，收进来只会把跨域耦合引进库。</li>
 * </ul>
 */
public interface DdlDialect extends SqlDialect {

    /** 产品名（{@code DatabaseMetaData.getDatabaseProductName()}）是否归本方言管，大小写不敏感。 */
    boolean matches(String productName);

    /** 长文本列的类型名（MariaDB 用 {@code LONGTEXT}，SQLite 用 {@code TEXT}）。 */
    String textType();

    /**
     * 自增主键列定义（整段，含列名）。
     *
     * <p>两者写法完全不同，不能靠拼字符串兼容：MariaDB 是
     * {@code id BIGINT PRIMARY KEY AUTO_INCREMENT}，SQLite 是
     * {@code id INTEGER PRIMARY KEY AUTOINCREMENT}——SQLite 里 {@code INTEGER PRIMARY KEY}
     * 本身就是 rowid 别名，写成 {@code BIGINT} 会失去自增语义。</p>
     */
    String autoIncrementPrimaryKey();

    /** {@code CREATE TABLE IF NOT EXISTS} 是否受支持（SQLite 与 MariaDB 都支持；为演进留出出口）。 */
    boolean supportsCreateTableIfNotExists();

    /** {@code CREATE INDEX IF NOT EXISTS} 是否受支持（SQLite 支持，MySQL/MariaDB 不支持）。 */
    boolean supportsIndexIfNotExists();

    /** 列是否存在。用 {@code SELECT <col> FROM <t> WHERE 1=0} 直接探测（跨方言可用）。 */
    boolean columnExists(Connection connection, String tableName, String column);

    /**
     * 幂等补列：缺列才执行 {@code ALTER TABLE … ADD COLUMN}。
     *
     * <p>返回是否真的执行了 ALTER——调用方据此判断"这次启动改了结构"，而不是每次启动都静默跳过。</p>
     *
     * @throws SQLException ALTER 失败（权限、锁超时、语法错误）如实上抛；
     *      吞掉它会让 schema 半迁移且无告警，直到后续 SELECT 才报未知列
     */
    boolean addColumnIfMissing(Connection connection, String tableName, String column, String columnDdl)
            throws SQLException;

    /**
     * 执行 {@code INSERT} 并取回自增主键。
     *
     * <p>方言差异在于"取回"的方式：JDBC 标准是 {@code RETURN_GENERATED_KEYS} + {@code getGeneratedKeys()}，
     * 部分驱动在缺失时返回空结果集，需要回退。把这段收在一处，避免每个插件各写一遍。</p>
     *
     * @param sql   带占位符的 INSERT 语句
     * @param binder 依次绑定参数的调用
     * @return 生成的主键
     * @throws SQLException 未取回主键，或执行失败
     */
    long insertReturningKey(Connection connection, String sql, StatementBinder binder) throws SQLException;

    /** 参数绑定（{@link DdlDialect#insertReturningKey} 用）。 */
    @FunctionalInterface
    interface StatementBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }
}
