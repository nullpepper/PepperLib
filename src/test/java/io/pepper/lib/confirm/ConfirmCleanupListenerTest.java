package io.pepper.lib.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfirmCleanupListenerTest {

    @Test
    void onQuitClearsTheQuittingPlayersEntry() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final ConfirmCleanupListener listener = new ConfirmCleanupListener(registry);

        final UUID playerId = UUID.randomUUID();
        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);

        registry.register(playerId, "action-1", 10_000);
        assertEquals(1, registry.size());

        final PlayerQuitEvent event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);

        listener.onQuit(event);

        assertTrue(registry.consume(playerId).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void onQuitLeavesOtherPlayersEntriesUntouched() {
        final ConfirmRegistry<String> registry = new ConfirmRegistry<>();
        final ConfirmCleanupListener listener = new ConfirmCleanupListener(registry);

        final UUID quitter = UUID.randomUUID();
        final UUID bystander = UUID.randomUUID();
        registry.register(quitter, "a", 10_000);
        registry.register(bystander, "b", 10_000);

        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(quitter);
        final PlayerQuitEvent event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);

        listener.onQuit(event);

        assertEquals(1, registry.size());
        assertTrue(registry.consume(bystander).isPresent());
    }

    @Test
    void rejectsNullRegistry() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new ConfirmCleanupListener(null));
    }
}
