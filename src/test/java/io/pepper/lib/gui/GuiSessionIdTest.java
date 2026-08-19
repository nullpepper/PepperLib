package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuiSessionIdTest {

    @Test
    void recordCarriesAllComponents() {
        final UUID player = UUID.randomUUID();
        final UUID instance = UUID.randomUUID();
        final GuiSessionId session = new GuiSessionId(player, instance, 3L);
        assertEquals(player, session.playerId());
        assertEquals(instance, session.instanceId());
        assertEquals(3L, session.version());
    }

    @Test
    void rejectsNullPlayerId() {
        assertThrows(NullPointerException.class, () -> new GuiSessionId(null, UUID.randomUUID(), 1L));
    }

    @Test
    void rejectsNullInstanceId() {
        assertThrows(NullPointerException.class, () -> new GuiSessionId(UUID.randomUUID(), null, 1L));
    }

    @Test
    void equalityIsStructural() {
        final UUID player = UUID.randomUUID();
        final UUID instance = UUID.randomUUID();
        assertEquals(new GuiSessionId(player, instance, 1L), new GuiSessionId(player, instance, 1L));
        assertNotEquals(new GuiSessionId(player, instance, 1L), new GuiSessionId(player, instance, 2L));
    }
}
