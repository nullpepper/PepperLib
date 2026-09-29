package ltd.pepper.lib.storage.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连接池失败探针：把"建连失败的真实原因"打到日志。
 *
 * <p>这些断言在修复前**必须红**：现状是连续失败完全静默（实测 130 秒挂起期间 INFO 级零失败原因）。</p>
 */
class PoolDiagnosticsTest {

    private final ExecutorService maintenance =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "pepper-pool-maintenance"));
    private final List<String> lines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        maintenance.shutdownNow();
    }

    private PoolDiagnostics diagnostics(final int threshold, final PoolDiagnostics.ConnectionProbe probe) {
        return new PoolDiagnostics("test-pool", threshold, probe, maintenance, lines::add);
    }

    @Test
    @DisplayName("未达阈值：不探测、不打日志，但计数必须累计")
    void countsFailuresButStaysSilentBelowThreshold() throws Exception {
        final AtomicReference<String> probedOn = new AtomicReference<>();
        final Connection connection = mock(Connection.class);
        final PoolDiagnostics diagnostics = diagnostics(3, () -> {
            probedOn.set(Thread.currentThread().getName());
            return connection;
        });

        diagnostics.recordFailure(new SQLException("boom-1"));
        diagnostics.recordFailure(new SQLException("boom-2"));

        assertEquals(2, diagnostics.consecutiveFailures(), "连续失败必须累计");
        Thread.sleep(200L);
        assertTrue(lines.isEmpty(), "未达阈值不应打日志：" + lines);
        assertEquals(null, probedOn.get(), "未达阈值不应探测");
    }

    @Test
    @DisplayName("达阈值：在维护线程上探测，并打出含原始异常类名的根因行")
    void logsRootCauseOnMaintenanceThreadAtThreshold() throws Exception {
        final AtomicReference<String> probedOn = new AtomicReference<>();
        final PoolDiagnostics diagnostics = diagnostics(3, () -> {
            probedOn.set(Thread.currentThread().getName());
            throw new SQLException("Access denied for user 'pepperclaim'@'172.22.0.3'");
        });

        diagnostics.recordFailure(new SQLException("HikariPool-1 - Connection is not available, request timed out"));
        diagnostics.recordFailure(new SQLException("HikariPool-1 - Connection is not available, request timed out"));
        assertEquals(2, diagnostics.consecutiveFailures());

        // 调用线程（main）不得被探针阻塞
        diagnostics.recordFailure(new SQLException("HikariPool-1 - Connection is not available, request timed out"));
        assertEquals(3, diagnostics.consecutiveFailures());

        final String line = awaitLine();
        assertTrue(line.contains(PoolDiagnostics.PREFIX), "缺统一前缀：" + line);
        assertTrue(line.contains("name=test-pool"), "缺池名：" + line);
        assertTrue(line.contains("consecutiveFailures=3"), "缺计数：" + line);
        assertTrue(line.contains("cause=java.sql.SQLException"), "根因行必须含原始异常类名（否则生产仍看不到原因）：" + line);
        assertTrue(line.contains("Access denied"), "根因行必须含原始异常消息：" + line);
        assertTrue(line.contains("probeMillis="), "根因行应含探测耗时：" + line);
        assertEquals("pepper-pool-maintenance", probedOn.get(), "探针必须跑在维护线程上，而不是调用线程");
    }

    @Test
    @DisplayName("探针成功（库其实可达）时也必须关闭探针连接，并说明库可达")
    void closesProbeConnectionWhenProbeSucceeds() throws Exception {
        final Connection connection = mock(Connection.class);
        final PoolDiagnostics diagnostics = diagnostics(1, () -> connection);

        diagnostics.recordFailure(new SQLException("HikariPool-1 - Connection is not available, request timed out"));

        final String line = awaitLine();
        assertTrue(line.contains("probe=ok"), "库可达时应显式说明（用于区分连接问题与池内部问题）：" + line);
        assertTrue(line.contains("cause=java.sql.SQLException"), line);
        verify(connection).close();
    }

    @Test
    @DisplayName("探针自身失败不能外抛，且其异常就是根因行")
    void probeFailureDoesNotPropagateAndIsLogged() throws Exception {
        final PoolDiagnostics diagnostics = diagnostics(1, () -> {
            throw new SQLException("Communications link failure");
        });

        diagnostics.recordFailure(new SQLException("timeout"));

        final String line = awaitLine();
        assertTrue(line.contains("cause=java.sql.SQLException"), line);
        assertTrue(line.contains("Communications link failure"), "探针异常应作为根因：" + line);
    }

    @Test
    @DisplayName("成功一次即清零连续失败计数")
    void successResetsCounter() throws Exception {
        final PoolDiagnostics diagnostics = diagnostics(3, () -> mock(Connection.class));

        diagnostics.recordFailure(new SQLException("boom-1"));
        diagnostics.recordFailure(new SQLException("boom-2"));
        assertEquals(2, diagnostics.consecutiveFailures(), "清零前必须真的累计到 2（否则本测试是空转的）");
        diagnostics.recordSuccess();
        assertEquals(0, diagnostics.consecutiveFailures());

        diagnostics.recordFailure(new SQLException("boom-3"));
        diagnostics.recordFailure(new SQLException("boom-4"));
        Thread.sleep(200L);
        assertFalse(lines.size() > 0, "清零后不应达阈值：" + lines);
    }

    private String awaitLine() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!lines.isEmpty()) {
                return lines.get(0);
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("5 秒内没有等到探针日志行");
    }
}
