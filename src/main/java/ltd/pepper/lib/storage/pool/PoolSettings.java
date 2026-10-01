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
 * @param socketTimeoutMs 驱动读写超时（写入 JDBC URL）；**0 = 本类不写入该参数**（保持驱动默认，即无界读）。
 *     读超时是每连接全局生效，可能砍掉合法的长语句，因此**默认不启用**；必须由调用方按该插件最长合法
 *     语句显式给出上界，见 {@link #withSocketTimeout(long)}（H2）。
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
                10_000L, // 驱动建连超时：有界（实测驱动默认亦为 30s，但显式写死更可控）
                0L // socketTimeout 默认不写：读超时全局生效，可能砍掉合法长语句（H2），由调用方显式开启
                );
    }

    /** 显式开启驱动读超时（{@code socketTimeout}）。 */
    public PoolSettings withSocketTimeout(final long socketTimeoutMs) {
        return new PoolSettings(
                this.maximumPoolSize,
                this.minimumIdle,
                this.connectionTimeoutMs,
                this.maxLifetimeMs,
                this.keepaliveMs,
                this.validationTimeoutMs,
                this.connectTimeoutMs,
                socketTimeoutMs);
    }

    /**
     * 生产级固定容量连接池（Fixed Pool 原则：minimumIdle == maximumPoolSize）。
     *
     * <p>对齐 HikariCP 官方生产环境推荐，消除并发突增时动态建连的 TCP/TLS 握手延迟毛刺。</p>
     */
    public static PoolSettings fixedPool(final int poolSize, final long connectionTimeoutMs) {
        final int size = Math.max(1, poolSize);
        final long validation = Math.max(1L, Math.min(2_000L, connectionTimeoutMs / 2));
        return new PoolSettings(
                size,
                size,
                connectionTimeoutMs,
                1_800_000L, // 30min 生命周期退役
                30_000L, // 30s 空闲连接保活探测
                validation,
                5_000L, // 5s 建连超时
                15_000L // 15s 读写超时，防止底层挂起
                );
    }

    /**
     * SQLite 专用单连接池（单写者模型）。
     */
    public static PoolSettings sqliteSinglePool(final long connectionTimeoutMs) {
        return new PoolSettings(
                1,
                1,
                connectionTimeoutMs,
                0L, // SQLite 本地文件不需要连接淘汰
                0L, // 本地连接不需要 keepalive
                Math.max(1L, Math.min(1_000L, connectionTimeoutMs / 2)),
                5_000L,
                0L);
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

    /**
     * 生产级属性装配：包含连接初始化 SQL（SQLite PRAGMA / MySQL 会话隔离）、连接泄漏检测（10s）与 Fail-Fast 设置。
     */
    public Properties toProductionProperties(
            final String poolName,
            final String jdbcUrl,
            final String username,
            final String password,
            final boolean isSqlite) {
        final Properties properties = toHikariProperties(poolName, jdbcUrl, username, password);
        properties.setProperty("leakDetectionThreshold", "10000");
        // 生产环境遵循快速失败（Fail-Fast）：10 秒内连不上立即暴露，杜绝带病运行
        properties.setProperty("initializationFailTimeout", "10000");
        if (isSqlite) {
            properties.setProperty(
                    "connectionInitSql",
                    "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL;");
        } else {
            properties.setProperty("connectionInitSql", "SET SESSION sql_mode='TRADITIONAL';");
        }
        return properties;
    }

    /**
     * 把驱动级超时写进 URL（已有查询参数时追加）。
     *
     * <p>{@code connectTimeout} 恒定写入（建连必须有界）；{@code socketTimeout} 仅在
     * {@link #withSocketTimeout(long)} 显式开启后才写入（H2：读超时可能砍掉合法长语句）。</p>
     *
     * <p><b>不接受 SQLite 裸路径</b>（{@code jdbc:sqlite:/path/db}）：sqlite-jdbc 把查询串当成
     * <b>文件名的一部分</b>，追加 {@code ?connectTimeout=…} 会另开一个空库，表现为"数据全没了"
     * （F15，2026-09-30 实测）。这条守卫刻意放在库里而不是各消费方：原先三个消费方各自判断
     * （Claim {@code JdbcStorage}、Union {@code SqlSupport}、BindManager 的 sqlite 分支），
     * 第四个消费者照签名调用就会中招。{@code jdbc:sqlite:file:…}（URI 形态）参数是正常解析的，
     * 因此放行。</p>
     *
     * @throws IllegalArgumentException 传入的是 SQLite 裸路径
     */
    public String jdbcUrlWithTimeouts(final String jdbcUrl) {
        if (isBareSqlitePath(jdbcUrl)) {
            throw new IllegalArgumentException("jdbcUrlWithTimeouts 不接受 SQLite 裸路径（" + jdbcUrl + "）：sqlite-jdbc 会把追加的"
                    + "查询参数当成文件名的一部分，从而另开一个空库。请对 SQLite 使用原始 URL，"
                    + "或在需要参数时改用 file: URI 形态（jdbc:sqlite:file:...）。");
        }
        final StringBuilder url = new StringBuilder(jdbcUrl);
        url.append(jdbcUrl.indexOf('?') >= 0 ? '&' : '?');
        url.append("connectTimeout=").append(this.connectTimeoutMs);
        if (this.socketTimeoutMs > 0L) {
            url.append("&socketTimeout=").append(this.socketTimeoutMs);
        }
        return url.toString();
    }

    /**
     * 是否为「不支持查询参数」的 SQLite URL：SQLite 的绝对/相对裸路径都属此类；
     * {@code file:} URI 形态支持参数（内存库 {@code file:memdb?mode=memory} 正依赖这一点）。
     */
    public static boolean isBareSqlitePath(final String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.startsWith("jdbc:sqlite:file:");
    }
}
