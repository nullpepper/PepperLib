package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方言的<b>契约套件</b>：同一套断言跑遍每个 {@link DdlDialect} 实现。
 *
 * <p><b>为什么要"套件"而不是每个方言各写一份测试</b>：方言的正确性由"实现是否满足同一组不变量"
 * 定义，而不是由各自测各自定义。分开写就会出现"MariaDB 的测试只测了它自己认的写法"——那正是
 * 三个插件各写一份方言、口径逐渐漂移的成因。</p>
 *
 * <p><b>给后来者的用法</b>：新增一个方言实现（或插件侧薄包装）后，把它的工厂加进
 * {@link #DIALECTS} 即可自动获得全部断言；插件不需要再复制这些测试。</p>
 *
 * <p>范围说明：纯函数型不变量（类型映射、能力位、产品名匹配）对<b>所有</b>方言断言；
 * 需要真实连接的部分只在 SQLite 上跑（本仓库测试环境只有 SQLite 驱动，MariaDB 的连接受环境门控）。</p>
 */
class DdlDialectContractTest {

    /** 待验方言工厂：实现方言时把新条目加到这里。 */
    private static final java.util.Map<String, DdlDialect> DIALECTS = java.util.Map.of(
            "sqlite",
                    new SqliteDdlDialect() {
                        @Override
                        public Class<? extends java.sql.Driver> driverClass() {
                            return org.sqlite.JDBC.class;
                        }
                    },
            // 注意：这里绑 SQLite 驱动而不是 MariaDB 驱动——MariaDB 驱动不在 PepperLib 的测试类路径上
            // （DDL 方言与驱动无关，driverClass() 由插件侧绑定，契约套件不需要真正的那个驱动）。
            "mariadb",
                    new MariaDbDdlDialect() {
                        @Override
                        public Class<? extends java.sql.Driver> driverClass() {
                            return org.sqlite.JDBC.class;
                        }
                    });

    private static DdlDialect sqlite() {
        return DIALECTS.get("sqlite");
    }

    // ==================== 对所有方言的不变量 ====================

    @Test
    @DisplayName("契约：类型映射与自增主键片段非空，且自增片段带 id 列（不能只给类型不给列）")
    void typesAndPrimaryKeyAreUsable() {
        DIALECTS.forEach((name, dialect) -> {
            assertNotNull(dialect.textType(), name + ": textType 不得为 null");
            assertFalse(dialect.textType().isBlank(), name + ": textType 不得为空");
            assertNotNull(dialect.autoIncrementPrimaryKey(), name + ": 自增主键片段不得为 null");
            assertTrue(
                    dialect.autoIncrementPrimaryKey().contains("id"),
                    name + ": 自增主键片段应含 id 列名，实际：" + dialect.autoIncrementPrimaryKey());
            assertTrue(
                    dialect.autoIncrementPrimaryKey()
                            .toUpperCase(java.util.Locale.ROOT)
                            .contains("KEY"),
                    name + ": 自增主键片段应声明主键，实际：" + dialect.autoIncrementPrimaryKey());
        });
    }

    @Test
    @DisplayName("契约：产品名匹配只认自己那一族，且对 null 安全")
    void productNameMatchingIsExclusive() {
        DIALECTS.forEach((name, dialect) -> {
            assertFalse(dialect.matches(null), name + ": matches(null) 必须为 false（不能 NPE）");
            assertTrue(dialect.matches(name), name + ": 应匹配自己的产品名");
        });

        DdlDialect sqlite = sqlite();
        DdlDialect maria = DIALECTS.get("mariadb");
        assertTrue(maria.matches("MySQL"), "MariaDB 方言应同时认 MySQL");
        assertTrue(maria.matches("MariaDB"));
        assertFalse(maria.matches("SQLite"), "MariaDB 方言不得认 SQLite");
        assertFalse(sqlite.matches("MySQL"), "SQLite 方言不得认 MySQL");
        assertFalse(sqlite.matches("MariaDB"));
    }

    @Test
    @DisplayName("契约：能力位两两一致——只有 SQLite 支持 CREATE INDEX IF NOT EXISTS")
    void capabilityFlagsDistinguishDialects() {
        DIALECTS.forEach((name, dialect) ->
                assertTrue(dialect.supportsCreateTableIfNotExists(), name + ": CREATE TABLE IF NOT EXISTS 两种方言都支持"));

        assertTrue(sqlite().supportsIndexIfNotExists(), "SQLite 支持 CREATE INDEX IF NOT EXISTS");
        assertFalse(
                DIALECTS.get("mariadb").supportsIndexIfNotExists(),
                "MySQL/MariaDB 不支持 CREATE INDEX IF NOT EXISTS（那里须用普通 CREATE INDEX + 幂等处理）");
    }

    @Test
    @DisplayName("契约：onConnect 对 null 连接安全（MariaDB 是空操作，不能 NPE）")
    void onConnectToleratesNullForNoOpDialects() throws SQLException {
        // SQLite 的 onConnect 需要真连接，单独在下一个用例里验；这里只要求"不做无谓操作"的方言不炸。
        DIALECTS.get("mariadb").onConnect(null);
    }

    // ==================== 需要真实连接（SQLite）的部分 ====================

    @Test
    @DisplayName("契约：DDL 片段能被真驱动接受，且自增真的生效（两行主键递增）")
    void generatedDdlWorksOnRealDatabase() throws Exception {
        DdlDialect dialect = sqlite();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            dialect.onConnect(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE contract_t (" + dialect.autoIncrementPrimaryKey() + ", name "
                        + dialect.textType() + ")");
            }
            assertTrue(dialect.tableExists(connection, "contract_t"));
            assertFalse(dialect.tableExists(connection, "contract_t_missing"));

            long first = dialect.insertReturningKey(
                    connection, "INSERT INTO contract_t (name) VALUES (?)", ps -> ps.setString(1, "a"));
            long second = dialect.insertReturningKey(
                    connection, "INSERT INTO contract_t (name) VALUES (?)", ps -> ps.setString(1, "b"));
            assertEquals(1L, first);
            assertEquals(2L, second, "自增应递增；若自增片段写成 BIGINT PRIMARY KEY 这里会失败");
        }
    }

    @Test
    @DisplayName("契约：幂等补列——缺列才改，重复调用不得再 ALTER")
    void addColumnIsIdempotentOnRealDatabase() throws Exception {
        DdlDialect dialect = sqlite();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (" + dialect.autoIncrementPrimaryKey() + ")");
            }

            assertTrue(dialect.addColumnIfMissing(connection, "t", "note", dialect.textType() + " NULL"));
            assertTrue(dialect.columnExists(connection, "t", "note"));
            assertFalse(
                    dialect.addColumnIfMissing(connection, "t", "note", dialect.textType() + " NULL"),
                    "已有列时再 ALTER 会报 duplicate column，必须返回 false");
        }
    }

    @Test
    @DisplayName("契约：columnExists 对缺表/缺列都返回 false，而不是抛异常")
    void columnExistsIsTotal() throws Exception {
        DdlDialect dialect = sqlite();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            }
            assertFalse(dialect.columnExists(connection, "t", "missing"));
            assertFalse(dialect.columnExists(connection, "no_such_table", "id"), "表不存在时应返回 false（调用方据此决定是否建表）");
        }
    }
}
