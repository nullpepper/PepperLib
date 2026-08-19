package io.pepper.lib.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.pepper.lib.validation.Preconditions;
import org.jetbrains.annotations.Nullable;

/**
 * HikariCP 连接池工厂：统一两插件的建池参数与 shadow 环境驱动注册。
 *
 * <p>沉淀 PepperClaim {@code JdbcStorage.init()} 的建池配置与
 * {@code registerDriver()} 的 Shadow relocation 双名回退经验
 * （PepperLib-Skeleton-Draft §1.5）。</p>
 */
public final class ConnectionPoolFactory {

    private ConnectionPoolFactory() {}

    /**
     * 创建连接池。
     *
     * @param jdbcUrl JDBC 连接串
     * @param poolName 池名（监控/日志识别）
     * @param maxPoolSize 最大连接数（下限 2）
     * @param connectionTimeoutMs 获取连接超时（毫秒）
     * @param username 用户名；{@code null} 或空白表示无认证
     * @param password 密码（仅用户名有效时使用）
     * @return 已就绪的数据源
     * @throws IllegalArgumentException 参数非法
     */
    public static HikariDataSource create(
            final String jdbcUrl,
            final String poolName,
            final int maxPoolSize,
            final long connectionTimeoutMs,
            @Nullable final String username,
            @Nullable final String password) {
        Preconditions.requireNotBlank(jdbcUrl, "jdbcUrl");
        Preconditions.requireNotBlank(poolName, "poolName");
        Preconditions.requireNonNegative(maxPoolSize, "maxPoolSize");
        if (connectionTimeoutMs < 0) {
            throw new IllegalArgumentException("connectionTimeoutMs must be >= 0, got " + connectionTimeoutMs);
        }
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setPoolName(poolName);
        config.setMaximumPoolSize(Math.max(2, maxPoolSize));
        config.setConnectionTimeout(connectionTimeoutMs);
        if (username != null && !username.isBlank()) {
            config.setUsername(username);
            config.setPassword(password);
        }
        return new HikariDataSource(config);
    }

    /**
     * 显式注册 JDBC 驱动（不依赖 {@code META-INF/services} 服务文件）。
     *
     * <p>Shadow 打包实测不会合并 {@code java.sql.Driver} 的 MariaDB 条目
     * （仅 sqlite 条目进产物），relocate 后的驱动无法被 {@code DriverManager}
     * 自动发现——按候选类名逐一 {@code Class.forName} 触发驱动静态注册。
     * shadow 环境优先 relocate 名，裸环境（IDE/测试）回退原名。</p>
     *
     * @param relocatedCandidates shadow 环境下的 relocation 类名
     * @param plainCandidates 裸环境类名
     */
    public static void registerDriver(final String[] relocatedCandidates, final String[] plainCandidates) {
        for (final String candidate : relocatedCandidates) {
            tryRegister(candidate);
        }
        for (final String candidate : plainCandidates) {
            tryRegister(candidate);
        }
    }

    private static void tryRegister(final String candidate) {
        try {
            Class.forName(candidate);
        } catch (final ClassNotFoundException ignored) {
            // 候选类名在当环境不存在时跳过（shadow 名与裸名互斥，至多一个命中）。
        }
    }
}
