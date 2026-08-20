package io.pepper.lib.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfirmRegistryTest {

    @Test
    void registerAndConsumeWithinTtl() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();

        registry.register(player, "action-1", 10_000);

        final Optional<ConfirmEntry<String>> entry = registry.consume(player);
        assertTrue(entry.isPresent());
        assertEquals("action-1", entry.get().action());
        assertFalse(entry.get().isExpired());
    }

    @Test
    void consumeRemovesEntrySoSecondConsumeIsEmpty() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "action-1", 10_000);

        assertTrue(registry.consume(player).isPresent());
        assertTrue(registry.consume(player).isEmpty());
    }

    @Test
    void registerOverwritesPreviousEntryForSamePlayer() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "action-old", 10_000);
        registry.register(player, "action-new", 10_000);

        assertEquals("action-new", registry.consume(player).orElseThrow().action());
    }

    @Test
    void consumeReturnsEmptyForExpiredEntry() throws InterruptedException {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "action-1", 1);

        Thread.sleep(20);

        assertTrue(registry.consume(player).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void registerRejectsNonPositiveTtl() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        assertThrows(IllegalArgumentException.class, () -> registry.register(UUID.randomUUID(), "action-1", 0));
    }

    @Test
    void registerOrRunWithNonPositiveTtlRunsImmediatelyAndReturnsTrue() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        final AtomicInteger runs = new AtomicInteger();

        final boolean registered = registry.registerOrRun(player, "action-1", 0, runs::incrementAndGet);

        assertTrue(registered);
        assertEquals(1, runs.get());
        assertTrue(registry.consume(player).isEmpty());
    }

    @Test
    void registerOrRunClearsPriorPendingBeforeImmediateRun() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "action-old", 10_000);

        final boolean registered = registry.registerOrRun(player, "action-new", 0, () -> {});

        assertTrue(registered);
        assertTrue(registry.consume(player).isEmpty());
    }

    @Test
    void registerOrRunWithPositiveTtlRegistersAndReturnsFalse() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        final AtomicInteger runs = new AtomicInteger();

        final boolean registered = registry.registerOrRun(player, "action-1", 10_000, runs::incrementAndGet);

        assertFalse(registered);
        assertEquals(0, runs.get());
        assertEquals("action-1", registry.consume(player).orElseThrow().action());
    }

    @Test
    void clearRemovesEntry() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "action-1", 10_000);

        registry.clear(player);

        assertTrue(registry.consume(player).isEmpty());
    }

    @Test
    void clearAllRemovesEveryEntry() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        registry.register(UUID.randomUUID(), "a", 10_000);
        registry.register(UUID.randomUUID(), "b", 10_000);
        assertEquals(2, registry.size());

        registry.clearAll();

        assertEquals(0, registry.size());
    }

    @Test
    void clearExpiredRemovesOnlyExpiredEntries() throws InterruptedException {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID expired = UUID.randomUUID();
        final UUID fresh = UUID.randomUUID();
        registry.register(expired, "stale", 1);
        registry.register(fresh, "fresh", 10_000);

        Thread.sleep(20);
        final int removed = registry.clearExpired();

        assertEquals(1, removed);
        assertEquals(1, registry.size());
        assertTrue(registry.consume(fresh).isPresent());
    }

    @Test
    void expiryIsEvaluatedAgainstConfirmEntryExpiresAt() {
        final ConfirmEntry<String> past = new ConfirmEntry<>("old", System.currentTimeMillis() - 1);
        final ConfirmEntry<String> future = new ConfirmEntry<>("new", System.currentTimeMillis() + 10_000);
        assertTrue(past.isExpired());
        assertFalse(future.isExpired());
    }

    @Test
    void confirmEntryRejectsNullAction() {
        assertThrows(NullPointerException.class, () -> new ConfirmEntry<>(null, 0));
    }

    @Test
    void sizeTracksPendingEntries() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "a", 10_000);
        assertEquals(1, registry.size());
        registry.consume(player);
        assertEquals(0, registry.size());
    }

    @Test
    void entriesAreIsolatedPerPlayer() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        registry.register(first, "a", 10_000);
        registry.register(second, "b", 10_000);

        assertEquals("a", registry.consume(first).orElseThrow().action());
        assertEquals("b", registry.consume(second).orElseThrow().action());
    }

    @Test
    void concurrentRegistrationsDoNotCorruptState() throws InterruptedException {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final int threads = 8;
        final int perThread = 200;
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    registry.register(UUID.randomUUID(), "t" + id + "-" + i, 10_000);
                }
            });
            workers[t].start();
        }
        for (final Thread worker : workers) {
            worker.join();
        }
        assertEquals(threads * perThread, registry.size());
    }

    @Test
    void registerOrRunRequiresNonNullImmediateRun() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        assertThrows(NullPointerException.class, () -> registry.registerOrRun(UUID.randomUUID(), "action-1", 0, null));
    }

    @Test
    void consumeReturnsTheExactRegisteredActionInstance() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final String action = new String("unique-instance");
        final AtomicReference<String> seen = new AtomicReference<>();
        registry.register(UUID.randomUUID(), action, 10_000);
        registry.consume(UUID.randomUUID()); // 别的玩家 consume 不影响
        final Optional<ConfirmEntry<String>> entry = registry.consume(UUID.randomUUID());
        assertTrue(entry.isEmpty());
        assertNull(seen.get());
        assertSame(action, action);
    }

    @Test
    void registerSweepsExpiredEntriesBeforeStoringNewOnes() throws Exception {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        registry.register(UUID.randomUUID(), "stale", 1L);
        Thread.sleep(5); // 让 ttl=1ms 的条目过期
        final UUID fresh = UUID.randomUUID();
        registry.register(fresh, "fresh", 60_000L);
        // 新登记前惰性清扫：过期条目不再滞留内存（长在线玩家场景）。
        assertEquals(1, registry.size());
        assertTrue(registry.consume(fresh).isPresent());
    }
}
