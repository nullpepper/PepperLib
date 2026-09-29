package ltd.pepper.lib.storage.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 连接池失败探针：让"建连失败的真实原因"出现在日志里。
 *
 * <p><b>为什么需要它</b>：HikariCP 把建连失败打在 DEBUG（{@code Failed to create/setup connection}），
 * 且 {@code addConnectionExecutor.submit()} 返回的 Future 异常无人取用；实测 130 秒网络挂起期间
 * INFO 级日志**只有池的 Starting/StartCompleted**，失败原因一条都没有。生产上因此出现"6 小时
 * 只看到超时、看不到原因"。本类在连续失败达阈值时，用一个**独立连接**去取原始异常并打日志。</p>
 *
 * <p>线程纪律：探针跑在**维护线程**（{@code maintenance}）上，绝不占用调用线程，也不占用
 * Hikari 的单线程 adder；探针连接在 finally 中关闭。</p>
 *
 * <p>日志形态（生产可 grep {@code [storage-pool]}）：</p>
 * <pre>
 * [storage-pool] name=PepperClaimPool consecutiveFailures=3 probeMillis=12 probe=failed cause=java.sql.SQLException: Access denied ...
 * [storage-pool] name=PepperClaimPool consecutiveFailures=6 probeMillis=8 probe=ok cause=java.sql.SQLException: HikariPool-1 - Connection is not available ...
 * </pre>
 * {@code probe=ok} 表示"库其实可达"——用于区分连接层问题与池内部问题，这是生产上最缺的一格信息。
 *
 * <p>可测性：日志出口（{@code log}）、维护执行器、探针连接工厂都可注入。</p>
 */
public final class PoolDiagnostics {

    /** 探针日志统一前缀，便于生产 grep。 */
    public static final String PREFIX = "[storage-pool]";

    /** 独立探针连接（不复用 Hikari 池，否则池故障时探针也拿不到连接）。 */
    @FunctionalInterface
    public interface ConnectionProbe {
        Connection open() throws SQLException;
    }

    private final String poolName;
    private final int threshold;
    private final ConnectionProbe probe;
    private final Executor maintenance;
    private final Consumer<String> log;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    public PoolDiagnostics(
            final String poolName,
            final int threshold,
            final ConnectionProbe probe,
            final Executor maintenance,
            final Consumer<String> log) {
        this.poolName = poolName;
        this.threshold = Math.max(1, threshold);
        this.probe = probe;
        this.maintenance = maintenance;
        this.log = log;
    }

    /** 连续失败计数（测试与诊断命令使用）。 */
    public int consecutiveFailures() {
        return this.consecutiveFailures.get();
    }

    /** 取连接成功：清零连续失败计数。 */
    public void recordSuccess() {
        this.consecutiveFailures.set(0);
    }

    /**
     * 取连接失败：累计；达阈值（含其后每个阈值倍数）时在维护线程上探测并打出根因。
     *
     * <p>本方法**绝不抛异常**、**绝不阻塞调用线程**：故障路径上的诊断不能反过来放大故障。</p>
     */
    public void recordFailure(final Throwable cause) {
        final int failures = this.consecutiveFailures.incrementAndGet();
        if (failures < this.threshold || failures % this.threshold != 0) {
            return;
        }
        // 单飞：探针可能比失败节奏慢，避免堆积出探测风暴。
        if (!this.probeInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            this.maintenance.execute(() -> this.runProbe(cause));
        } catch (final RuntimeException rejected) {
            // 维护执行器已关闭：放弃本次探测，但不清空计数（下一个阈值倍数还会试）。
            this.probeInFlight.set(false);
        }
    }

    private void runProbe(final Throwable poolFailure) {
        final long start = System.nanoTime();
        boolean reachable = false;
        Throwable rootCause = poolFailure;
        try (Connection connection = this.probe.open()) {
            reachable = connection != null;
        } catch (final Throwable probeFailure) {
            // 探针自己的异常就是根因：它带着"为什么连不上"的原始信息（拒绝/超时/认证/DNS）。
            rootCause = probeFailure;
        } finally {
            this.probeInFlight.set(false);
        }
        final long millis = (System.nanoTime() - start) / 1_000_000L;
        try {
            this.log.accept(PREFIX + " name=" + this.poolName + " consecutiveFailures="
                    + this.consecutiveFailures.get() + " probeMillis=" + millis + " probe="
                    + (reachable ? "ok" : "failed") + " cause=" + describe(rootCause));
        } catch (final RuntimeException ignored) {
            // 日志出口故障同样不得外抛
        }
    }

    private static String describe(final Throwable failure) {
        if (failure == null) {
            return "(null)";
        }
        final String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
