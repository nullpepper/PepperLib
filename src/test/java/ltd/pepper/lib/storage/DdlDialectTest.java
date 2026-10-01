package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DDL 方言的真库契约（SQLite 上跑真实语句）。
 *
 * <p>为什么必须真库：这一层的价值全在"生成的 DDL 能不能被驱动接受"——类型名、自增主键写法、
 * 生成键取回方式，都只有在真实驱动上才验得出来。上一轮审出的 SQLite URL 陷阱（F15）就是
 * "看着对、真库上另开空库"的典型。</p>
 *
 * <p>测试用的方言继承 {@link SqliteDdlDialect} 并绑定驱动（与插件将要采用的形态一致：
 * DDL 在库里、{@code driverClass()} 在被 shade 的一侧）。</p>
 */
class DdlDialectTest {

    /** 测试方言：DDL 全部来自库，只绑定驱动类。 */
    private static final class TestSqliteDialect extends SqliteDdlDialect {
        @Override
        public Class<? extends java.sql.Driver> driverClass() {
            return org.sqlite.JDBC.class;
        }
    }

    private final DdlDialect dialect = new TestSqliteDialect();
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    @DisplayName("能力判定与类型映射：SQLite 支持 IF NOT EXISTS（表与索引），长文本用 TEXT")
    void capabilitiesAndTypes() {
        assertTrue(dialect.isSqlite());
        assertTrue(dialect.supportsCreateTableIfNotExists());
        assertTrue(dialect.supportsIndexIfNotExists(), "SQLite 支持 CREATE INDEX IF NOT EXISTS");
        assertEquals("TEXT", dialect.textType());
        assertEquals("id INTEGER PRIMARY KEY AUTOINCREMENT", dialect.autoIncrementPrimaryKey());
        assertTrue(dialect.matches("SQLite"));
        assertTrue(dialect.matches("sqlite"));
        assertFalse(dialect.matches("MySQL"));
        assertFalse(dialect.matches(null));
    }

    @Test
    @DisplayName("onConnect：PRAGMA 真的生效（foreign_keys 默认关闭，必须显式打开）")
    void onConnectAppliesPragmas() throws Exception {
        dialect.onConnect(connection);

        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "foreign_keys 应为 ON");
        }
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next());
            assertEquals(5000, rs.getInt(1), "busy_timeout 应为 5000");
        }
    }

    @Test
    @DisplayName("生成的建表 DDL 能被真库接受（自增主键片段直接可用）")
    void generatedPrimaryKeyIsAcceptedByRealDriver() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS t (" + dialect.autoIncrementPrimaryKey() + ", name "
                    + dialect.textType() + " NOT NULL)");
        }

        assertTrue(dialect.tableExists(connection, "t"));
        assertFalse(dialect.tableExists(connection, "missing_table"), "不存在的表必须判为 false");
    }

    @Test
    @DisplayName("自增语义真的成立：连插两行，主键自动递增（写成 BIGINT 会丢掉这一点）")
    void autoIncrementActuallyIncrements() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (" + dialect.autoIncrementPrimaryKey() + ", v TEXT)");
        }

        long first = dialect.insertReturningKey(
                connection, "INSERT INTO t (v) VALUES (?)", statement -> statement.setString(1, "a"));
        long second = dialect.insertReturningKey(
                connection, "INSERT INTO t (v) VALUES (?)", statement -> statement.setString(1, "b"));

        assertEquals(1L, first);
        assertEquals(2L, second, "第二行主键应为 2，说明自增生效");
    }

    @Test
    @DisplayName("columnExists：用直接探测判列存在（不依赖 DatabaseMetaData 的 catalog 匹配）")
    void columnExistenceIsProbedDirectly() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        }

        assertTrue(dialect.columnExists(connection, "t", "name"));
        assertFalse(dialect.columnExists(connection, "t", "nope"));
        assertFalse(dialect.columnExists(connection, "missing_table", "name"), "表不存在时也应判为 false 而不是抛异常");
    }

    @Test
    @DisplayName("addColumnIfMissing：缺列才补，返回是否真的 ALTER 过；重复调用幂等")
    void addColumnIfMissingIsIdempotent() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
        }

        assertTrue(dialect.addColumnIfMissing(connection, "t", "nickname", "TEXT NULL"), "首次应真的执行 ALTER");
        assertTrue(dialect.columnExists(connection, "t", "nickname"));
        assertFalse(
                dialect.addColumnIfMissing(connection, "t", "nickname", "TEXT NULL"),
                "已有列时不得再 ALTER（重复执行会报 duplicate column）");
    }

    @Test
    @DisplayName("addColumnIfMissing：ALTER 真的失败时如实上抛，不吞掉（否则 schema 半迁移且无告警）")
    void addColumnFailuresAreNotSwallowed() throws Exception {
        // 表不存在 → ALTER 必然失败；这里要的是"抛出来"，而不是被静默忽略。
        assertThrows(
                SQLException.class, () -> dialect.addColumnIfMissing(connection, "no_such_table", "c", "TEXT NULL"));
    }

    @Test
    @DisplayName("insertReturningKey：取不到生成键时抛错，而不是返回 0 或 -1 这种假主键")
    void missingGeneratedKeyFailsLoudly() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (" + dialect.autoIncrementPrimaryKey() + ", v TEXT)");
        }

        // 纯 SELECT 不产生生成键。
        assertThrows(SQLException.class, () -> dialect.insertReturningKey(connection, "SELECT 1", statement -> {}));
    }
}
