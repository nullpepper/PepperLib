package io.pepper.lib.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据库迁移：按版本号升序执行一次。
 *
 * <p>迁移必须自行处理方言差异（SQLite 与 MySQL/MariaDB），并在成功完成后
 * 返回；{@link MigrationRunner} 负责记录已应用版本。</p>
 *
 * <p>统一两插件契约：PepperUnion / PepperClaim 的 {@code Migration} 均适配本接口
 * （PepperUnion 的 {@code requiredColumns} 校验能力一并纳入）。</p>
 */
public interface Migration {

    /** 版本号（全局唯一、严格递增）。 */
    int version();

    /** 迁移名（记录用）。 */
    String name();

    /**
     * 应用迁移。
     *
     * @param connection 连接（SQLite 路径下由 runner 包在事务内）
     * @param dialect 方言（处理 SQLite / MySQL 差异）
     * @throws SQLException 迁移失败
     */
    void migrate(Connection connection, SqlDialect dialect) throws SQLException;

    /**
     * 声明迁移完成后必须存在的表列（表名 → 列名）。{@link MigrationRunner}
     * 在迁移后统一校验，用于拒绝「旧表存在但缺列」且不会被
     * {@code CREATE TABLE IF NOT EXISTS} 补齐的旧库。
     */
    default Map<String, List<String>> requiredColumns() {
        return Map.of();
    }
}
