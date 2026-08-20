package io.pepper.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.i18n.FakePapiPlugin;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link BukkitPepperScheduler} 行为测试（mockbukkit）：
 * 主线程判定、runTask/runAsync/runRepeating/supplyOnMain。
 */
class BukkitPepperSchedulerTest {

    private ServerMock server;
    private BukkitPepperScheduler scheduler;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.scheduler = new BukkitPepperScheduler(MockBukkit.load(FakePapiPlugin.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void isMainThreadFalseOnWorkerThread() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<Boolean> result = pool.submit(() -> this.scheduler.isMainThread());
            assertFalse(result.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void runTaskExecutesOnTick() {
        final AtomicBoolean ran = new AtomicBoolean();
        this.scheduler.runTask(() -> ran.set(true));
        this.server.getScheduler().performTicks(1);
        assertTrue(ran.get());
    }

    @Test
    void runAsyncExecutesEventually() throws Exception {
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        this.scheduler.runAsync(() -> done.complete(true));
        assertTrue(done.get(5, TimeUnit.SECONDS));
    }

    @Test
    void runRepeatingExecutesPerTick() {
        final AtomicInteger count = new AtomicInteger();
        this.scheduler.runRepeating(count::incrementAndGet, 0, 1);
        this.server.getScheduler().performTicks(3);
        assertTrue(count.get() >= 2, "expected repeating execution, got " + count.get());
    }

    @Test
    void supplyOnMainReturnsResult() {
        final CompletableFuture<String> future = this.scheduler.supplyOnMain(() -> "ok");
        this.server.getScheduler().performTicks(1);
        assertEquals("ok", future.join());
    }
}
