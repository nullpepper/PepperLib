package io.pepper.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** PepperScheduler 接口契约：用立即执行假实现验证分发与 future 语义。 */
class PepperSchedulerContractTest {

    /** 测试假实现：全部在当前线程立即执行；记录最后一次重复任务参数。 */
    private static final class ImmediateScheduler implements PepperScheduler {

        private final AtomicInteger mainRuns = new AtomicInteger();
        private final AtomicInteger asyncRuns = new AtomicInteger();
        private long lastDelayTicks;
        private long lastPeriodTicks;

        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void runTask(final Runnable task) {
            this.mainRuns.incrementAndGet();
            task.run();
        }

        @Override
        public void runAsync(final Runnable task) {
            this.asyncRuns.incrementAndGet();
            task.run();
        }

        @Override
        public void runRepeating(final Runnable task, final long delayTicks, final long periodTicks) {
            this.lastDelayTicks = delayTicks;
            this.lastPeriodTicks = periodTicks;
            task.run();
        }

        @Override
        public <T> CompletableFuture<T> supplyOnMain(final java.util.function.Supplier<T> supplier) {
            // 当前线程立即执行；异常按 supplyAsync 语义进入返回的 future。
            return CompletableFuture.supplyAsync(supplier, Runnable::run);
        }
    }

    @Test
    void runTaskAndRunAsyncDispatch() {
        final ImmediateScheduler scheduler = new ImmediateScheduler();
        scheduler.runTask(() -> {});
        scheduler.runAsync(() -> {});
        assertEquals(1, scheduler.mainRuns.get());
        assertEquals(1, scheduler.asyncRuns.get());
    }

    @Test
    void runRepeatingPassesDelayAndPeriod() {
        final ImmediateScheduler scheduler = new ImmediateScheduler();
        scheduler.runRepeating(() -> {}, 20L, 100L);
        assertEquals(20L, scheduler.lastDelayTicks);
        assertEquals(100L, scheduler.lastPeriodTicks);
    }

    @Test
    void supplyOnMainCompletesWithSupplierValue() {
        final ImmediateScheduler scheduler = new ImmediateScheduler();
        final CompletableFuture<String> future = scheduler.supplyOnMain(() -> "value");
        assertEquals("value", future.join());
    }

    @Test
    void supplyOnMainPropagatesSupplierFailure() {
        final ImmediateScheduler scheduler = new ImmediateScheduler();
        final CompletableFuture<String> future = scheduler.supplyOnMain(() -> {
            throw new IllegalStateException("boom");
        });
        try {
            future.join();
        } catch (final CompletionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void isMainThreadReflectsFakeSemantics() {
        final ImmediateScheduler scheduler = new ImmediateScheduler();
        assertTrue(scheduler.isMainThread());
    }
}
