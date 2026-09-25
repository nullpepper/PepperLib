package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 生产回归（2026-09-25 MariaDB）：{@code DatabaseMetaData.getColumns(null, null, table, null)}
 * 在 catalog=null 时会跨库匹配同名表——别的库里的 {@code schema_migrations} 带 checksum 列，
 * 导致本库「列已存在」的假阳性、跳过 ALTER，随后 {@code SELECT checksum} 报未知列，
 * 迁移直接失败（PepperBotBindManager 在 MariaDB 上启动即挂）。
 *
 * <p>本测试用「谎报 checksum 列存在」的元数据包装连接复现该场景：列检测必须
 * 直接探测目标表，而不是信任元数据。</p>
 */
class MigrationRunnerMetadataQuirkTest {

    private static final class TestSqliteDialect implements SqlDialect {
        @Override
        public boolean isSqlite() {
            return true;
        }

        @Override
        public void onConnect(final Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
            }
        }

        @Override
        public boolean tableExists(final Connection connection, final String tableName) throws SQLException {
            try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
                return rs.next();
            }
        }

        @Override
        public Class<? extends java.sql.Driver> driverClass() {
            return org.sqlite.JDBC.class;
        }
    }

    private Connection connection;
    private final SqlDialect dialect = new TestSqliteDialect();

    private static Migration migration(final int version, final String name, final String checksum) {
        return new Migration() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public void migrate(final Connection connection, final SqlDialect dialect) {}

            @Override
            public String checksum() {
                return checksum;
            }
        };
    }

    /** 包装连接：getColumns 谎报存在 checksum 列（模拟 MariaDB 跨库假阳性）。 */
    private static Connection withLyingMetadata(final Connection real) {
        final InvocationHandler metaHandler = (proxy, method, args) -> {
            if ("getColumns".equals(method.getName())) {
                return fakeChecksumResultSet();
            }
            return method.invoke(real.getMetaData(), args);
        };
        final Object lyingMeta = Proxy.newProxyInstance(
                MigrationRunnerMetadataQuirkTest.class.getClassLoader(),
                new Class<?>[] {java.sql.DatabaseMetaData.class},
                metaHandler);
        final InvocationHandler connectionHandler = (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return lyingMeta;
            }
            return method.invoke(real, args);
        };
        return (Connection) Proxy.newProxyInstance(
                MigrationRunnerMetadataQuirkTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                connectionHandler);
    }

    /** 只回答一行 COLUMN_NAME=checksum 的结果集。 */
    private static ResultSet fakeChecksumResultSet() {
        final boolean[] first = {true};
        final InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                final boolean value = first[0];
                first[0] = false;
                yield value;
            }
            case "getString" -> "checksum";
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (ResultSet) Proxy.newProxyInstance(
                MigrationRunnerMetadataQuirkTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler);
    }

    @BeforeEach
    void setUp() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.connection.close();
    }

    @Test
    void lyingMetadataDoesNotSkipChecksumColumnUpgrade() throws SQLException {
        // 旧库形态：版本表已存在但没有 checksum 列，且 v1 已记录。
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY,"
                    + " name VARCHAR(255) NOT NULL, applied_at BIGINT NOT NULL)");
            statement.execute("INSERT INTO schema_migrations (version, name, applied_at) VALUES (1, 'v1', 0)");
        }

        new MigrationRunner(List.of(migration(1, "v1", "sha-any"))).run(withLyingMetadata(this.connection), this.dialect);

        try (Statement statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT checksum FROM schema_migrations WHERE version = 1")) {
            assertEquals(true, rs.next(), "checksum 列必须已被补齐");
        }
    }
}
