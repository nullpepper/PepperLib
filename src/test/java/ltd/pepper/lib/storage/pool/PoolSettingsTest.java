package ltd.pepper.lib.storage.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连接池设置：显式上界与 F1/F2 的硬约束。
 *
 * <p>这些断言在修复前**必须红**：现状是 {@code minimumIdle == maximumPoolSize}、
 * URL 不带 {@code connectTimeout}/{@code socketTimeout}、{@code maxLifetime}/{@code keepaliveTime}/
 * {@code validationTimeout} 全为默认或未设。</p>
 */
class PoolSettingsTest {

    private static HikariConfig effective(final PoolSettings settings) {
        return new HikariConfig(settings.toHikariProperties("test-pool", "jdbc:mariadb://h:3306/db", "u", "p"));
    }

    @Test
    @DisplayName("minimumIdle 必须严格小于 maximumPoolSize（否则 HouseKeeper 补池与健康维护被整块跳过）")
    void minimumIdleStrictlyBelowMaximum() {
        final PoolSettings settings = PoolSettings.forPoolSize(10, 5_000L);
        assertTrue(
                settings.minimumIdle() < settings.maximumPoolSize(),
                "设置里 minimumIdle=" + settings.minimumIdle() + " 不满足 < maximumPoolSize=" + settings.maximumPoolSize());

        final HikariConfig config = effective(settings);
        assertMinimumIdleEffective(config);
    }

    @Test
    @DisplayName("H2：connectTimeout 恒定写入；socketTimeout 默认不写，必须显式开启")
    void jdbcUrlTimeoutsAreExplicitAndSocketTimeoutIsOptIn() {
        final PoolSettings settings = PoolSettings.forPoolSize(10, 5_000L);
        assertNotEquals(0L, settings.connectTimeoutMs(), "connectTimeout 不允许为 0（建连必须有界）");
        assertEquals(0L, settings.socketTimeoutMs(), "socketTimeout 默认必须为 0 = 不写入（避免砍掉合法长语句）");

        final String url = settings.jdbcUrlWithTimeouts("jdbc:mariadb://127.0.0.1:3306/db");
        assertTrue(url.contains("connectTimeout=" + settings.connectTimeoutMs()), "URL 缺少 connectTimeout：" + url);
        assertFalse(url.contains("socketTimeout"), "默认不得写入 socketTimeout（H2）：" + url);

        final String withExistingParam =
                settings.jdbcUrlWithTimeouts("jdbc:mariadb://127.0.0.1:3306/db?useUnicode=true");
        assertTrue(withExistingParam.contains("useUnicode=true"), "不得丢弃已有查询参数：" + withExistingParam);
        assertTrue(withExistingParam.contains("connectTimeout="), "已有参数时仍须追加 connectTimeout：" + withExistingParam);

        // 显式开启后才写入，且值取自调用方（判据：必须大于该插件最长合法语句）
        final PoolSettings withReadTimeout = settings.withSocketTimeout(120_000L);
        final String optedIn = withReadTimeout.jdbcUrlWithTimeouts("jdbc:mariadb://127.0.0.1:3306/db");
        assertTrue(optedIn.contains("socketTimeout=120000"), "显式开启后必须写入设定值：" + optedIn);
    }

    @Test
    @DisplayName("Hikari 生效上界与设置一致（connectionTimeout/maxLifetime/keepalive/validation）")
    void hikariHonoursExplicitUpperBounds() {
        final PoolSettings settings = PoolSettings.forPoolSize(10, 5_000L);
        final HikariConfig config = effective(settings);

        assertEquals(5_000L, config.getConnectionTimeout(), "connectionTimeout 应取自调用方");
        assertEquals(settings.maxLifetimeMs(), config.getMaxLifetime(), "maxLifetime 必须是显式上界");
        assertEquals(settings.keepaliveMs(), config.getKeepaliveTime(), "keepaliveTime 必须是显式上界");
        assertEquals(settings.validationTimeoutMs(), config.getValidationTimeout(), "validationTimeout 必须是显式上界");
        assertTrue(
                config.getValidationTimeout() < config.getConnectionTimeout(),
                "validationTimeout 必须 < connectionTimeout");
        assertTrue(config.getMaxLifetime() > 0L, "maxLifetime 不得为 0（0 = 永不淘汰）");
        assertEquals(-1L, config.getInitializationFailTimeout(), "不得在构造期建连");
    }

    private static void assertMinimumIdleEffective(final HikariConfig config) {
        config.validate();
        final int minIdle = config.getMinimumIdle();
        assertTrue(minIdle >= 1, "minimumIdle 从未设置（validate 后仍为 -1）：" + minIdle);
        assertTrue(
                minIdle < config.getMaximumPoolSize(),
                "minimumIdle=" + minIdle + " 必须 < max=" + config.getMaximumPoolSize());
    }

    @Test
    @DisplayName("F15：SQLite 裸路径必须拒绝而不是被拼上 ?connectTimeout=（否则另开空库、数据“消失”）")
    void rejectsBareSqlitePaths() {
        final PoolSettings settings = PoolSettings.forPoolSize(2, 5_000L);

        // 绝对路径与相对路径都是裸路径：sqlite-jdbc 会把查询串当文件名的一部分。
        for (final String bare :
                new String[] {"jdbc:sqlite:/tmp/x/plain.db", "jdbc:sqlite:rel.db", "jdbc:sqlite:pepperclaim.db"}) {
            assertTrue(PoolSettings.isBareSqlitePath(bare), "应判为裸路径：" + bare);
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class, () -> settings.jdbcUrlWithTimeouts(bare), "必须拒绝：" + bare);
            assertTrue(refused.getMessage().contains("SQLite"), "错误消息要点明是 SQLite：" + refused.getMessage());
        }
    }

    @Test
    @DisplayName("F15 反面：file: URI 形态仍可带参数通过（内存库依赖这一点）")
    void allowsSqliteFileUriForm() {
        final PoolSettings settings = PoolSettings.forPoolSize(2, 5_000L);

        // 内存库/URI 形态的参数是正常解析的，不能因为“是 sqlite”就一刀切拒绝。
        final String memoryUrl = "jdbc:sqlite:file:memdb?mode=memory&cache=shared";
        assertFalse(PoolSettings.isBareSqlitePath(memoryUrl), "file: URI 不是裸路径：" + memoryUrl);

        final String withTimeout = settings.jdbcUrlWithTimeouts(memoryUrl);
        assertTrue(withTimeout.contains("mode=memory"), "不得丢弃已有参数：" + withTimeout);
        assertTrue(withTimeout.contains("connectTimeout="), "应追加 connectTimeout：" + withTimeout);

        // 文件型 URI 同样放行。
        assertFalse(PoolSettings.isBareSqlitePath("jdbc:sqlite:file:/tmp/x/uri.db"));
        assertTrue(
                settings.jdbcUrlWithTimeouts("jdbc:sqlite:file:/tmp/x/uri.db").contains("connectTimeout="));
    }

    @Test
    @DisplayName("非 SQLite URL（MariaDB/MySQL）不受该守卫影响")
    void nonSqliteUrlsAreUnaffected() {
        final PoolSettings settings = PoolSettings.forPoolSize(2, 5_000L);

        assertFalse(PoolSettings.isBareSqlitePath("jdbc:mariadb://h:3306/db"));
        assertFalse(PoolSettings.isBareSqlitePath("jdbc:mysql://h:3306/db"));
        assertFalse(PoolSettings.isBareSqlitePath(null));
        assertTrue(settings.jdbcUrlWithTimeouts("jdbc:mariadb://h:3306/db").contains("connectTimeout="));
    }
}
