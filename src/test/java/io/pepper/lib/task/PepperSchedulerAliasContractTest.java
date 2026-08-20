package io.pepper.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link PepperScheduler} 遗留别名契约（设计文档 docs/extraction-a2-plan.md §1.2）：
 * 旧名（runTaskAsynchronously / runTaskTimer / supplyOnMainThread）default 委托
 * 规范名（runAsync / runRepeating / supplyOnMain），存量调用名不受影响。
 */
class PepperSchedulerAliasContractTest {

    /** 记录式假实现：只实现规范名（canonical），验证旧名委托。 */
    private static final class RecordingScheduler implements PepperScheduler {
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void runTask(final Runnable task) {
            this.calls.add("runTask");
        }

        @Override
        public void runAsync(final Runnable task) {
            this.calls.add("runAsync");
        }

        @Override
        public void runRepeating(final Runnable task, final long delayTicks, final long periodTicks) {
            this.calls.add("runRepeating");
        }

        @Override
        public <T> CompletableFuture<T> supplyOnMain(final Supplier<T> supplier) {
            this.calls.add("supplyOnMain");
            return CompletableFuture.completedFuture(supplier.get());
        }
    }

    @Test
    void legacyNamesDelegateToCanonicalNames() {
        final RecordingScheduler scheduler = new RecordingScheduler();
        scheduler.runTaskAsynchronously(() -> {});
        scheduler.runTaskTimer(() -> {}, 1, 2);
        scheduler.supplyOnMainThread(() -> "x");
        assertEquals(List.of("runAsync", "runRepeating", "supplyOnMain"), scheduler.calls);
    }

    @Test
    void legacyNamesReturnCanonicalResults() {
        final RecordingScheduler scheduler = new RecordingScheduler();
        assertTrue(scheduler.supplyOnMainThread(() -> true).join());
    }
}
