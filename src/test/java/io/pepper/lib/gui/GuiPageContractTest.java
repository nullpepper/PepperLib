package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 最小页面协议契约：GuiPage / GuiContext / GuiItemFactory 的可测试形态。 */
class GuiPageContractTest {

    private record TestContext(UUID playerId, GuiSessionId session) implements GuiContext {
        @Override
        public io.pepper.lib.task.PepperScheduler scheduler() {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class RecordingPage implements GuiPage<String> {

        private String renderedState;
        private GuiClick lastClick;
        private boolean closed;

        @Override
        public Inventory render(final GuiContext context, final String state) {
            this.renderedState = state;
            return Mockito.mock(Inventory.class);
        }

        @Override
        public void click(final GuiContext context, final String state, final GuiClick click) {
            this.lastClick = click;
        }

        @Override
        public void close(final GuiContext context, final String state) {
            this.closed = true;
        }
    }

    @Test
    void pageDispatchesRenderClickAndClose() {
        final RecordingPage page = new RecordingPage();
        final GuiContext context =
                new TestContext(UUID.randomUUID(), new GuiSessionId(UUID.randomUUID(), UUID.randomUUID(), 1L));

        final Inventory inventory = page.render(context, "state-1");
        assertEquals("state-1", page.renderedState);
        assertSame(inventory, inventory);

        final GuiClick click = new GuiClick(
                5,
                org.bukkit.event.inventory.ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL,
                true);
        page.click(context, "state-1", click);
        assertSame(click, page.lastClick);

        page.close(context, "state-1");
        assertEquals(true, page.closed);
    }

    @Test
    void guiItemFactoryPassesThroughArguments() {
        final ItemStack item = Mockito.mock(ItemStack.class);
        final GuiItemFactory factory = (material, name, lore) -> item;

        final ItemStack created = factory.create(
                Material.STONE,
                net.kyori.adventure.text.Component.text("name"),
                List.of(net.kyori.adventure.text.Component.text("lore")));
        assertSame(item, created);
    }
}
