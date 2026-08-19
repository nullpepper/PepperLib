package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GuiEventGuardsTest {

    @Test
    void belongsToMatchesSamePlayerUuid() {
        final UUID playerId = UUID.randomUUID();
        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);

        assertTrue(GuiEventGuards.belongsTo(player, playerId));
    }

    @Test
    void belongsToRejectsDifferentPlayerUuid() {
        final Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        assertFalse(GuiEventGuards.belongsTo(player, UUID.randomUUID()));
    }

    @Test
    void belongsToRejectsNonPlayerHolder() {
        final InventoryHolder holder = Mockito.mock(InventoryHolder.class);
        assertFalse(GuiEventGuards.belongsTo(holder, UUID.randomUUID()));
    }

    @Test
    void belongsToRejectsNullHolder() {
        assertFalse(GuiEventGuards.belongsTo(null, UUID.randomUUID()));
    }

    @Test
    void touchesTopWhenAnySlotInTopRange() {
        assertTrue(GuiEventGuards.touchesTop(Set.of(0, 26), 27));
        assertTrue(GuiEventGuards.touchesTop(Set.of(26), 27));
    }

    @Test
    void touchesTopFalseWhenAllSlotsInBottomRange() {
        assertFalse(GuiEventGuards.touchesTop(Set.of(27, 35), 27));
        assertFalse(GuiEventGuards.touchesTop(Set.of(54), 27));
    }

    @Test
    void touchesTopFalseForEmptyOrNullSlots() {
        assertFalse(GuiEventGuards.touchesTop(Set.of(), 27));
        assertFalse(GuiEventGuards.touchesTop(null, 27));
    }

    @Test
    void touchesTopFalseForNonPositiveTopSize() {
        assertFalse(GuiEventGuards.touchesTop(Set.of(0), 0));
        assertFalse(GuiEventGuards.touchesTop(Set.of(0), -1));
    }
}
