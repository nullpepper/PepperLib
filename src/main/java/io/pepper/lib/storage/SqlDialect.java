package io.pepper.lib.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;

/**
 * 受支持数据库之间的 SQL 方言差异（公共核心）。
 *
 * <p>只暴露两插件语义一致的部分；插件专属 SQL 段（如 upsert、列定义）由
 * 插件侧扩展接口继承本核心（内部设计文档 skeleton-draft §1.4）。</p>
 */
public interface SqlDialect {

    /** 是否为 SQLite（迁移框架选择事务策略、插件选择 upsert 语法）。 */
    boolean isSqlite();

    /**
     * 按连接的初始化：SQLite 三条 PRAGMA（busy_timeout → WAL → foreign_keys），
     * MySQL/MariaDB 空操作。
     */
    void onConnect(Connection connection) throws SQLException;

    /** 表存在性检查（幂等迁移用）。 */
    boolean tableExists(Connection connection, String tableName) throws SQLException;

    /**
     * JDBC 驱动类。以类字面量的形式引用，以便 shading 重定位自动应用到该引用上。
     */
    Class<? extends Driver> driverClass();
}
