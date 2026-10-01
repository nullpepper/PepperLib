package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CacheInvalidationBusTest {

    @Test
    void localBusDispatchesToSubscribers() {
        final CacheInvalidationBus bus = CacheInvalidationBus.local();
        final List<CacheInvalidationBus.InvalidationMessage> received = new ArrayList<>();
        bus.subscribe(received::add);

        final UUID player = UUID.randomUUID();
        bus.invalidatePlayer(player);

        assertEquals(1, received.size());
        assertEquals("PLAYER", received.get(0).entityType());
        assertEquals(player.toString(), received.get(0).entityId());
    }

    @Test
    void distributedBusInvokesExternalPublisher() {
        final AtomicBoolean published = new AtomicBoolean(false);
        final CacheInvalidationBus bus = CacheInvalidationBus.distributed(msg -> {
            if ("GUILD".equals(msg.entityType()) && "101".equals(msg.entityId())) {
                published.set(true);
            }
        });

        bus.invalidateEntity("GUILD", "101");
        assertTrue(published.get(), "分布式模式必须触发外部发布器");
    }
}
