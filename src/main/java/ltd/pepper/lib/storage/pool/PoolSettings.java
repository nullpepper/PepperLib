package ltd.pepper.lib.storage.pool;

import java.util.Properties;

/**
 * 连接池设置（Hikari 无关的纯 JDK 值对象 + 属性工厂）。
 *
 * <p><b>为什么不直接产出 {@code HikariConfig}</b>：PepperLib 是 {@code compileOnly} 的前置插件，
 * 运行期由 {@code PepperLib.jar} 单独提供；而消费方把 HikariCP relocate 到自己的命名空间
 * （形如 {@code <plugin 包>.lib.com.zaxxer.hikari}）。若本类引用 {@code com.zaxxer.hikari.*}，
 * PepperLib 的类加载器将找不到该类型（{@code NoClassDefFoundError}）。因此这里只产出
 * {@link Properties}，由消费方执行 {@code new HikariConfig(properties)}。</p>
 *
 * <p>属性名必须与 {@code HikariConfig} 的 setter 名一致（{@code minimumIdle} → {@code setMinimumIdle}），
 * 否则 Hikari 会静默忽略——所以单元测试用真实 {@code HikariConfig} 断言**生效值**，而不是只断言 map。</p>
 *
 * @param maximumPoolSize 池上限
 * @param minimumIdle 池下限；**必须严格小于 {@link #maximumPoolSize}**，否则 Hikari 的
 *     {@code HouseKeeper} 会整块跳过补池与健康维护（F1/F2）
 * @param connectionTimeoutMs 从池取连接的最长等待
 * @param maxLifetimeMs 连接最长寿命；应小于数据库 {@code wait_timeout}
 * @param keepaliveMs 空闲连接保活探测周期
 * @param validationTimeoutMs 连接校验超时；必须小于 {@link #connectionTimeoutMs}
 * @param connectTimeoutMs 驱动建连超时（写入 JDBC URL）
 * @param socketTimeoutMs 驱动读写超时（写入 JDBC URL）；**0 表示无界**，本类不允许 0
 */
public record PoolSettings(
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long maxLifetimeMs,
        long keepaliveMs,
        long validationTimeoutMs,
        long connectTimeoutMs,
        long socketTimeoutMs) {

    /** 由池大小与取连接超时派生一套显式上界。 */
    public static PoolSettings forPoolSize(final int poolSize, final long connectionTimeoutMs) {
        final int maximum = Math.max(1, poolSize);
        // F1/F2：必须严格小于 maximumPoolSize，否则 Hikari 的 HouseKeeper 会整块跳过补池与健康维护。
        // maximumPoolSize == 1（SQLite 单写者）无法满足该约束：此时上限只能为 1，
        // 该池没有主动补池能力，恢复只能依赖可用性看门狗重建（见方案 §6.1）。
        final int minimum = maximum == 1 ? 1 : Math.max(1, Math.min(2, maximum - 1));
        // validationTimeout 必须严格小于 connectionTimeout（Hikari 的硬约束）。
        final long validation = Math.max(1L, Math.min(2_000L, connectionTimeoutMs / 2));
        return new PoolSettings(
                maximum,
                minimum,
                connectionTimeoutMs,
                900_000L, // maxLifetime 15min：远小于 MariaDB wait_timeout(28800s)
                120_000L, // keepaliveTime 2min：空闲连接保活，尽早暴露半开连接
                validation,
                10_000L, // 驱动建连超时
                30_000L // 驱动读写超时：0 是无界，绝不允许
                );
    }

    /** 供 Hikari 消费的属性（键名 == setter 名）。 */
    public Properties toHikariProperties(
            final String poolName, final String jdbcUrl, final String username, final String password) {
        final Properties properties = new Properties();
        properties.setProperty("poolName", poolName);
        properties.setProperty("jdbcUrl", jdbcUrl);
        properties.setProperty("maximumPoolSize", String.valueOf(this.maximumPoolSize));
        properties.setProperty("minimumIdle", String.valueOf(this.minimumIdle));
        properties.setProperty("connectionTimeout", String.valueOf(this.connectionTimeoutMs));
        if (!username.isEmpty()) {
            properties.setProperty("username", username);
            properties.setProperty("password", password);
        }
        if (this.maxLifetimeMs > 0L) {
            properties.setProperty("maxLifetime", String.valueOf(this.maxLifetimeMs));
        }
        if (this.keepaliveMs > 0L) {
            properties.setProperty("keepaliveTime", String.valueOf(this.keepaliveMs));
        }
        if (this.validationTimeoutMs > 0L) {
            properties.setProperty("validationTimeout", String.valueOf(this.validationTimeoutMs));
        }
        // 不在构造期建连：数据库不可用必须表现为首次取连接失败，而不是插件启用失败。
        properties.setProperty("initializationFailTimeout", "-1");
        return properties;
    }

    /** 把驱动级超时显式写进 URL（已有查询参数时追加）。 */
    public String jdbcUrlWithTimeouts(final String jdbcUrl) {
        final StringBuilder url = new StringBuilder(jdbcUrl);
        url.append(jdbcUrl.indexOf('?') >= 0 ? '&' : '?');
        url.append("connectTimeout=").append(this.connectTimeoutMs);
        url.append("&socketTimeout=").append(this.socketTimeoutMs);
        return url.toString();
    }
}
