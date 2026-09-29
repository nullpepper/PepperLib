package ltd.pepper.lib.testing.libx;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;
import org.mockito.Mockito;

/**
 * 模拟"被 relocate 到插件命名空间"的 JDBC 驱动（H1 验证装置）。
 *
 * <p>关键特征与 shade 产物一致：**没有** {@code META-INF/services/java.sql.Driver} 条目，
 * 只靠静态块自我注册——因此 {@code DriverManager} 的 ServiceLoader 自动发现找不到它，
 * 必须先 {@code Class.forName}（等价各插件 {@code registerDriver()}）才可用。</p>
 */
public final class ShadedJdbcDriver implements Driver {

    static {
        try {
            DriverManager.registerDriver(new ShadedJdbcDriver());
        } catch (final SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Override
    public Connection connect(final String url, final Properties info) throws SQLException {
        return acceptsURL(url) ? Mockito.mock(Connection.class) : null;
    }

    @Override
    public boolean acceptsURL(final String url) {
        return url != null && url.startsWith("jdbc:libx:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("ltd.pepper.lib.testing.libx");
    }
}
