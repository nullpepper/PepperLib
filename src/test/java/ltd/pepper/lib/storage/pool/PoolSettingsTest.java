package ltd.pepper.lib.storage.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(
                config.getMinimumIdle() < config.getMaximumPoolSize(),
                "生效配置里 minimumIdle=" + config.getMinimumIdle() + " 必须 < maximumPoolSize="
                        + config.getMaximumPoolSize());
    }

    @Test
    @DisplayName("URL 必须显式带 connectTimeout 与 socketTimeout（socketTimeout=0 是无界读）")
    void jdbcUrlCarriesExplicitTimeouts() {
        final PoolSettings settings = PoolSettings.forPoolSize(10, 5_000L);
        assertNotEquals(0L, settings.socketTimeoutMs(), "socketTimeout 不允许为 0（无界）");
        assertNotEquals(0L, settings.connectTimeoutMs(), "connectTimeout 不允许为 0");

        final String url = settings.jdbcUrlWithTimeouts("jdbc:mariadb://127.0.0.1:3306/db");
        assertTrue(url.contains("connectTimeout=" + settings.connectTimeoutMs()), "URL 缺少 connectTimeout：" + url);
        assertTrue(url.contains("socketTimeout=" + settings.socketTimeoutMs()), "URL 缺少 socketTimeout：" + url);

        final String withExistingParam =
                settings.jdbcUrlWithTimeouts("jdbc:mariadb://127.0.0.1:3306/db?useUnicode=true");
        assertTrue(withExistingParam.contains("useUnicode=true"), "不得丢弃已有查询参数：" + withExistingParam);
        assertTrue(withExistingParam.contains("connectTimeout="), "已有参数时仍须追加超时：" + withExistingParam);
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
}
