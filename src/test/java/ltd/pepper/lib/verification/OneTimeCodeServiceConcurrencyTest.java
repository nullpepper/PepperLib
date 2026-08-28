package ltd.pepper.lib.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ltd.pepper.lib.verification.OneTimeCodeService.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 一次性验证码服务并发原子性（源自 PepperBotBindManager VerificationManagerConcurrencyTest）。 */
class OneTimeCodeServiceConcurrencyTest {

    @Test
    @DisplayName("并发 consume 同一验证码只有一次成功")
    void concurrentConsumeSingleWinner() throws Exception {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(6, false, 300, 60));
        String code = service.issue("payload", Duration.ofSeconds(300)).code();
        assertNotNull(code);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<OneTimeCodeService.Issued<String>> first = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                service.consume(code).ifPresent(issued -> {
                    winners.incrementAndGet();
                    first.compareAndSet(null, issued);
                });
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(1, winners.get(), "并发同码只能有一个成功者");
        assertNotNull(first.get());
        assertTrue(service.consume(code).isEmpty(), "验证码已被消费");
    }

    @Test
    @DisplayName("并发 tryAcquireCooldown 同一 key 只放行一次")
    void concurrentCooldownSinglePass() throws Exception {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(6, false, 300, 60));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (service.tryAcquireCooldown("same-key")) {
                    passed.incrementAndGet();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(1, passed.get(), "冷却期内同一 key 只能成功一次");
    }
}
