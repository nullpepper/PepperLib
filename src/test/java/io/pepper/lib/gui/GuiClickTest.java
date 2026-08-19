package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

class GuiClickTest {

    @Test
    void recordCarriesAllComponents() {
        final GuiClick click = new GuiClick(12, ClickType.LEFT, InventoryAction.PICKUP_ALL, true);
        assertEquals(12, click.rawSlot());
        assertEquals(ClickType.LEFT, click.clickType());
        assertEquals(InventoryAction.PICKUP_ALL, click.action());
        assertTrue(click.topInventory());
    }

    @Test
    void rejectsNullClickType() {
        assertThrows(NullPointerException.class, () -> new GuiClick(12, null, InventoryAction.PICKUP_ALL, true));
    }

    @Test
    void rejectsNullAction() {
        assertThrows(NullPointerException.class, () -> new GuiClick(12, ClickType.LEFT, null, true));
    }

    @Test
    void rejectsNegativeRawSlot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GuiClick(-1, ClickType.LEFT, InventoryAction.PICKUP_ALL, true));
    }
}
