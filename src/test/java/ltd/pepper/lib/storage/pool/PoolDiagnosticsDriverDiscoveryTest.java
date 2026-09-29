package ltd.pepper.lib.storage.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H1：探针的驱动发现必须在 shade/relocate 下可用。
 *
 * <p><b>为什么单测里的桩不够</b>：本类不桩 {@code ConnectionProbe}，而是走**真实的
 * {@code DriverManager} + 一个"被 relocate、且没有服务文件条目"的驱动**，复现项目已经
 * 踩过的坑（{@code JdbcStorage.registerDriver()} 注释：Shadow 不合并 {@code java.sql.Driver}
 * 的条目，relocate 后的驱动无法被自动发现）。若探针依赖裸 {@code DriverManager}，它会以
 * {@code No suitable driver} 报 {@code probe=failed}——**那是会把排查引向幽灵的假信号**，
 * 而这次的教训正是"别把误导信号当证据"。</p>
 *
 * <p><b>此处无"新行为"的红测</b>（按纪律如实声明）：本类验证的是**既有代码的契约**——
 * 一是复现隐患（未显式注册 ⇒ 失败），二是证明缓解措施成立（显式注册 ⇒ {@code probe=ok}）。
 * 验证方式即为下跑的真实连接路径；接入插件后还须在**带 relocate 的产物**里复跑同一断言。</p>
 */
class PoolDiagnosticsDriverDiscoveryTest {

    private static final String URL = "jdbc:libx:demo";

    @Test
    @DisplayName("H1：未显式注册驱动时 DriverManager 找不到（复现隐患）→ 显式注册后探针 probe=ok")
    void probeSucceedsOnlyAfterExplicitDriverRegistration() throws Exception {
        // 1) 复现隐患：shade 产物不合并 META-INF/services/java.sql.Driver，裸 DriverManager 找不到驱动
        final SQLException noDriver =
                assertThrows(SQLException.class, () -> DriverManager.getConnection(URL), "应当找不到驱动");
        assertTrue(
                noDriver.getMessage().contains("No suitable driver"),
                "应当是 No suitable driver（这正是会误导排查的假信号）：" + noDriver.getMessage());

        // 2) 显式注册：等价插件 registerDriver() 里 Class.forName(relocate 后的类名)
        Class.forName("ltd.pepper.lib.testing.libx.ShadedJdbcDriver");

        // 3) 探针经真实 DriverManager 必须 probe=ok
        final List<String> lines = new CopyOnWriteArrayList<>();
        final PoolDiagnostics diagnostics =
                new PoolDiagnostics("test-pool", 1, () -> DriverManager.getConnection(URL), Runnable::run, lines::add);

        diagnostics.recordFailure(new SQLException("HikariPool-1 - Connection is not available, request timed out"));

        assertEquals(1, lines.size(), "应恰好一条根因行：" + lines);
        final String line = lines.get(0);
        assertTrue(line.contains("probe=ok"), "显式注册后探针必须真的连上（probe=ok），而不是 No suitable driver：" + line);
        assertTrue(line.contains("cause=java.sql.SQLException"), line);
    }
}
