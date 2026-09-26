package ltd.pepper.lib.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * WP1 扩展行为：覆盖提示（register 报告被覆盖的未过期条目）、registerOrRun 覆盖回调、
 * 非消费 peek、条目创建时间戳（GUI 同 tick 双提交防护用）。
 */
class ConfirmRegistryExtensionTest {

    @Test
    void registerReportsDisplacedUnexpiredEntry() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "old", 10_000);

        final Optional<ConfirmEntry<String>> displaced = registry.register(player, "new", 10_000);

        assertTrue(displaced.isPresent(), "覆盖未过期条目时应报告被覆盖条目");
        assertEquals("old", displaced.get().action());
        assertEquals("new", registry.consume(player).orElseThrow().action());
    }

    @Test
    void registerOverExpiredEntryReportsNoDisplaced() throws InterruptedException {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "old", 1);
        Thread.sleep(5);

        assertTrue(registry.register(player, "new", 10_000).isEmpty(), "已过期条目不算覆盖");
    }

    @Test
    void registerOrRunInvokesOnOverwriteWithDisplacedEntry() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "old", 10_000);
        final AtomicReference<ConfirmEntry<String>> seen = new AtomicReference<>();

        final boolean immediate = registry.registerOrRun(player, "new", 10_000, () -> {}, seen::set);

        assertFalse(immediate);
        assertEquals("old", seen.get().action(), "覆盖回调应收到被覆盖的旧条目");
    }

    @Test
    void registerOrRunWithoutDisplacedDoesNotInvokeCallback() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        final AtomicReference<ConfirmEntry<String>> seen = new AtomicReference<>();

        registry.registerOrRun(player, "new", 10_000, () -> {}, seen::set);

        assertEquals(null, seen.get(), "无覆盖时不应触发回调");
    }

    @Test
    void peekDoesNotConsume() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "a", 10_000);

        assertEquals("a", registry.peek(player).orElseThrow().action());
        assertEquals("a", registry.consume(player).orElseThrow().action());
    }

    @Test
    void entryExposesCreationTimestamp() {
        final long before = System.currentTimeMillis();
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final UUID player = UUID.randomUUID();
        registry.register(player, "a", 10_000);
        final long after = System.currentTimeMillis();

        final long createdAt = registry.peek(player).orElseThrow().createdAt();
        assertTrue(createdAt >= before && createdAt <= after, "createdAt 应记录注册时刻");
    }
}
