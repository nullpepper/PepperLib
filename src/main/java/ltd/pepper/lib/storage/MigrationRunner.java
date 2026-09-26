package ltd.pepper.lib.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * 轻量级版本化迁移执行器（硬化版，源自 PepperUnion {@code MigrationRunner}）。
 *
 * <p>创建版本表（默认 {@code schema_migrations}，可参数化以兼容 Claim 的
 * {@code schema_version}），按版本升序执行尚未应用的迁移，每条成功后写入版本记录。
 * 崩溃安全保证：</p>
 *
 * <ul>
 *   <li><b>SQLite</b>：迁移执行 + 版本记录在<b>同一事务</b>内提交——崩溃
 *       （或执行失败）时整体回滚，重跑安全，不会出现「DDL 已生效但版本
 *       未记录」导致的永久启动失败。外键约束在事务外关闭/恢复。</li>
 *   <li><b>MySQL / MariaDB</b>：DDL 隐式提交、无法包事务，因此要求迁移
 *       自身幂等（全部使用 IF NOT EXISTS 或显式存在性检查），重跑安全。</li>
 * </ul>
 */
public final class MigrationRunner {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final List<Migration> migrations;
    private final String versionTableName;

    /** 使用默认版本表名 {@code schema_migrations}。 */
    public MigrationRunner(final List<Migration> migrations) {
        this(migrations, "schema_migrations");
    }

    /**
     * 使用自定义版本表名（兼容已有库，如 Claim 的 {@code schema_version}）。
     *
     * @param migrations 迁移列表（版本必须严格递增且唯一）
     * @param versionTableName 版本表名（仅字母数字下划线）
     * @throws IllegalArgumentException 版本乱序/重复，或表名非法
     */
    public MigrationRunner(final List<Migration> migrations, final String versionTableName) {
        if (versionTableName == null || versionTableName.isBlank()) {
            throw new IllegalArgumentException("versionTableName must not be blank");
        }
        if (!IDENTIFIER.matcher(versionTableName).matches()) {
            throw new IllegalArgumentException("versionTableName must match [A-Za-z0-9_]+");
        }
        final List<Migration> copy = List.copyOf(migrations);
        // 版本治理：版本号必须严格递增且唯一，防止重复/乱序迁移静默跳过。
        int previous = 0;
        for (final Migration migration : copy) {
            final int version = migration.version();
            if (version <= previous) {
                throw new IllegalArgumentException("Migration versions must be strictly increasing and unique, got "
                        + version + " after " + previous + " (" + migration.name() + ")");
            }
            previous = version;
        }
        this.migrations = copy;
        this.versionTableName = versionTableName;
    }

    /**
     * 执行未应用的迁移。
     *
     * @throws StorageException 迁移失败、已应用版本高于已知版本、必需列缺失
     */
    public void run(final Connection connection, final SqlDialect dialect) {
        try {
            this.ensureTable(connection);
            final Map<Integer, String> appliedRows = this.appliedRows(connection);
            this.verifyNoRemovedMigrations(appliedRows.keySet());
            this.verifyChecksums(appliedRows);
            for (final Migration migration : this.migrations) {
                if (appliedRows.containsKey(migration.version())) {
                    continue;
                }
                if (dialect.isSqlite()) {
                    this.runSqliteAtomic(connection, dialect, migration);
                } else {
                    // MySQL/MariaDB：DDL 隐式提交，迁移自身必须幂等。
                    migration.migrate(connection, dialect);
                    this.recordApplied(connection, dialect, migration);
                }
            }
            this.verifyRequiredColumns(connection);
        } catch (final SQLException e) {
            throw new StorageException("Failed to run database migrations: " + e.getMessage(), e);
        }
    }

    /**
     * 防呆：数据库已应用了比插件已知更高的版本 → 迁移被移除过，
     * 继续启动会带着「旧 schema 新代码」的未知状态，直接拒绝启动。
     */
    private void verifyNoRemovedMigrations(final Set<Integer> applied) {
        final Set<Integer> known = new HashSet<>();
        for (final Migration migration : this.migrations) {
            known.add(migration.version());
        }
        final Set<Integer> unknown = new TreeSet<>(applied);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new StorageException("Database contains unknown migration versions " + unknown
                    + " — migrations were removed or the plugin was downgraded; refusing to start");
        }
    }

    /**
     * checksum 漂移防护（PepperUnion #18）：已应用迁移的记录指纹必须与当前实现一致；
     * 不一致说明已应用的迁移内容被改动（或版本被复用），拒绝启动。
     * 旧库 NULL / 空 checksum 的行豁免（grandfather）。
     */
    private void verifyChecksums(final Map<Integer, String> appliedRows) {
        for (final Migration migration : this.migrations) {
            final String recorded = appliedRows.get(migration.version());
            if (recorded == null || recorded.isEmpty()) {
                continue;
            }
            final String current = migration.checksum();
            if (!recorded.equals(current)) {
                throw new StorageException("Migration " + migration.version() + " (" + migration.name()
                        + ") checksum mismatch: recorded [" + recorded + "] but current [" + current
                        + "] — applied migration content changed; refusing to start");
            }
        }
    }

    /**
     * 回滚失败事务并恢复 autoCommit。回滚或恢复任一步失败时关闭连接并返回
     * {@code false}——绝不能对「可能仍处于活动事务」的连接调用
     * {@code setAutoCommit(true)}，否则 JDBC 会先提交半截迁移。
     *
     * <p>包私有：仅为 {@code MigrationRunnerTest} 崩溃路径测试提供直接调用入口
     * （生产路径仅经 {@link #runSqliteAtomic} 使用）。</p>
     */
    @VisibleForTesting
    static boolean rollbackAndRestore(final Connection connection, final Throwable failure) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            closeBroken(connection, failure);
            return false;
        }
        try {
            connection.setAutoCommit(true);
            return true;
        } catch (final SQLException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            closeBroken(connection, failure);
            return false;
        }
    }

    private static void closeBroken(final Connection connection, final Throwable failure) {
        try {
            connection.close();
        } catch (final SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** SQLite：迁移 + 版本记录同事务；外键约束在事务外关闭/恢复。 */
    private void runSqliteAtomic(final Connection connection, final SqlDialect dialect, final Migration migration)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
        }
        connection.setAutoCommit(false);
        boolean connectionUsable = true;
        try {
            migration.migrate(connection, dialect);
            this.recordApplied(connection, dialect, migration);
            connection.commit();
            connection.setAutoCommit(true);
        } catch (final Throwable e) {
            // 失败/回滚异常都走同一个安全出口；连接已损坏时跳过 PRAGMA 恢复。
            connectionUsable = rollbackAndRestore(connection, e);
            if (e instanceof final SQLException sql) {
                throw sql;
            }
            throw new StorageException("Migration " + migration.version() + " (" + migration.name() + ") failed", e);
        } finally {
            if (connectionUsable) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
            }
        }
    }

    /** 校验所有迁移声明的必需列；旧表缺列时明确拒绝启动（同样走直接探测，见 {@link #hasColumn}）。 */
    private void verifyRequiredColumns(final Connection connection) throws SQLException {
        for (final Migration migration : this.migrations) {
            for (final Map.Entry<String, List<String>> table :
                    migration.requiredColumns().entrySet()) {
                for (final String required : table.getValue()) {
                    if (!this.hasColumn(connection, table.getKey(), required)) {
                        throw new SQLException("Migration " + migration.version() + " (" + migration.name()
                                + "): table " + table.getKey() + " is missing required column " + required);
                    }
                }
            }
        }
    }

    private void ensureTable(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + this.versionTableName + " ("
                    + "version INT PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "applied_at BIGINT NOT NULL, "
                    + "checksum VARCHAR(255))");
        }
        // 旧库升级：直接探测目标表，不信任 DatabaseMetaData——
        // MariaDB 下 getColumns(null, null, table, null) 会跨库匹配同名表，造成
        // 「列已存在」假阳性并跳过补列，随后 SELECT checksum 报未知列（生产回归 2026-09-25）。
        if (!this.hasColumn(connection, this.versionTableName, "checksum")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + this.versionTableName + " ADD COLUMN checksum VARCHAR(255)");
            }
        }
    }

    /**
     * 直接探测列是否存在（{@code SELECT <column> FROM <table> WHERE 1=0}）：跨方言可用，
     * 且不受各驱动 {@code DatabaseMetaData} 的 catalog/schema 匹配差异影响。
     */
    private boolean hasColumn(final Connection connection, final String table, final String column) {
        try (Statement statement = connection.createStatement()) {
            statement
                    .executeQuery("SELECT " + column + " FROM " + table + " WHERE 1=0")
                    .close();
            return true;
        } catch (final SQLException e) {
            return false;
        }
    }

    /** 已应用版本 → 记录的 checksum（旧库行为 NULL）。 */
    private Map<Integer, String> appliedRows(final Connection connection) throws SQLException {
        final Map<Integer, String> rows = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT version, checksum FROM " + this.versionTableName)) {
            while (rs.next()) {
                rows.put(rs.getInt("version"), rs.getString("checksum"));
            }
        }
        return rows;
    }

    private void recordApplied(final Connection connection, final SqlDialect dialect, final Migration migration)
            throws SQLException {
        // 多实例同时初始化同一 MySQL/MariaDB 库时，版本记录可能并发插入；
        // 使用方言幂等写入而不是裸 INSERT，避免主键冲突导致启动失败。
        final String sql = dialect.isSqlite()
                ? "INSERT OR IGNORE INTO " + this.versionTableName
                        + " (version, name, applied_at, checksum) VALUES (?, ?, ?, ?)"
                : "INSERT INTO " + this.versionTableName
                        + " (version, name, applied_at, checksum) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, migration.version());
            ps.setString(2, migration.name());
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, migration.checksum());
            ps.executeUpdate();
        }
    }
}
