package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.task.PepperScheduler;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link GuiHost} 事件管线测试（统一 GuiManager/ClaimGuiListener）：
 * 打开调度、事件守卫转发、null holder 放行、禁用清理、会话生命周期。
 */
class GuiHostTest {

    private ServerMock server;
    private GuiHost host;
    private PlayerMock player;

    private static final class ImmediateScheduler implements PepperScheduler {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void runTask(final Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(final Runnable task) {
            task.run();
        }

        @Override
        public void runRepeating(final Runnable task, final long delayTicks, final long periodTicks) {}

        @Override
        public <T> CompletableFuture<T> supplyOnMain(final Supplier<T> supplier) {
            return CompletableFuture.completedFuture(supplier.get());
        }
    }

    /** 记录型 GuiHolder：点击转发到 GuiClick 语义。 */
    private static final class RecordingHolder implements GuiHolder {
        final Player player;
        final AtomicInteger clicks = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        GuiClick lastClick;
        Inventory inventory;

        RecordingHolder(final Player player) {
            this.player = player;
        }

        @Override
        public Player getPlayer() {
            return this.player;
        }

        @Override
        public Inventory newInventory() {
            this.inventory = Bukkit.createInventory(this, 27, "Test");
            return this.inventory;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        @Override
        public void onClick(
                final InventoryAction action,
                final ClickType click,
                final org.bukkit.event.inventory.InventoryType.SlotType slotType,
                final int slot,
                final org.bukkit.inventory.ItemStack currentItem,
                final org.bukkit.inventory.ItemStack cursor,
                final org.bukkit.inventory.InventoryView view,
                final InventoryClickEvent event) {
            this.clicks.incrementAndGet();
            this.lastClick = new GuiClick(slot, click, action, true);
        }

        @Override
        public void onClose(final org.bukkit.inventory.InventoryView view) {
            this.closes.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.host = new GuiHost(MockBukkit.createMockPlugin(), new ImmediateScheduler());
        this.player = this.server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void openGuiOpensNextTickAndRoutesClick() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final Inventory top = this.player.getOpenInventory().getTopInventory();
        assertNotNull(top.getHolder(), "inventory must open on the next tick");
        assertEquals(holder, top.getHolder());

        final InventoryClickEvent event = new InventoryClickEvent(
                this.player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                5,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);

        assertEquals(1, holder.clicks.get());
        assertEquals(5, holder.lastClick.rawSlot());
        assertTrue(event.isCancelled(), "GUI clicks must be cancelled");
    }

    @Test
    void bottomShiftClickIsCancelledButNotRouted() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final org.bukkit.inventory.InventoryView view =
                org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(holder.inventory);
        final InventoryClickEvent event = org.mockito.Mockito.mock(InventoryClickEvent.class);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(this.player);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getClickedInventory()).thenReturn(this.player.getInventory());
        org.mockito.Mockito.when(event.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);

        this.host.onInventoryClick(event);

        org.mockito.Mockito.verify(event).setCancelled(true);
        assertEquals(0, holder.clicks.get(), "bottom shift-click must not reach menu business logic");
    }

    @Test
    void bottomCollectToCursorIsCancelledButNotRouted() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final org.bukkit.inventory.InventoryView view =
                org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(holder.inventory);
        final InventoryClickEvent event = org.mockito.Mockito.mock(InventoryClickEvent.class);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(this.player);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getClickedInventory()).thenReturn(this.player.getInventory());
        org.mockito.Mockito.when(event.getAction()).thenReturn(InventoryAction.COLLECT_TO_CURSOR);

        this.host.onInventoryClick(event);

        org.mockito.Mockito.verify(event).setCancelled(true);
        assertEquals(0, holder.clicks.get(), "bottom collect must not reach menu business logic");
    }

    @Test
    void ordinaryBottomClickPassesThrough() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final org.bukkit.inventory.InventoryView view =
                org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(holder.inventory);
        final InventoryClickEvent event = org.mockito.Mockito.mock(InventoryClickEvent.class);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(this.player);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getClickedInventory()).thenReturn(this.player.getInventory());
        org.mockito.Mockito.when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);

        this.host.onInventoryClick(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(0, holder.clicks.get());
    }

    @Test
    void clickOnNullHolderIsNotCancelled() {
        // 普通箱子（无 holder）/其他插件界面：放行，不进入菜单逻辑（原 GuiManager NPE 修复）。
        final org.bukkit.inventory.Inventory plain = Bukkit.createInventory(null, 27, "plain");
        this.player.openInventory(plain);

        final InventoryClickEvent event = new InventoryClickEvent(
                this.player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                5,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);

        assertTrue(!event.isCancelled(), "foreign/plain inventory clicks must pass through");
    }

    @Test
    void clickByDifferentPlayerIsCancelledButNotRouted() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final Player other = org.mockito.Mockito.mock(Player.class);
        org.mockito.Mockito.when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        final org.bukkit.inventory.InventoryView view =
                org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(holder.inventory);
        final InventoryClickEvent event = org.mockito.Mockito.mock(InventoryClickEvent.class);
        org.mockito.Mockito.when(event.getWhoClicked()).thenReturn(other);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);
        org.mockito.Mockito.when(event.getClickedInventory()).thenReturn(holder.inventory);

        this.host.onInventoryClick(event);

        // Union 语义：非归属玩家的点击完全不碰（不取消、不路由）。
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(0, holder.clicks.get(), "foreign player click must not reach the holder");
    }

    @Test
    void closeByDifferentPlayerDoesNotReachHolder() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final Player other = org.mockito.Mockito.mock(Player.class);
        org.mockito.Mockito.when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        final org.bukkit.inventory.InventoryView view =
                org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(holder.inventory);
        final org.bukkit.event.inventory.InventoryCloseEvent event =
                org.mockito.Mockito.mock(org.bukkit.event.inventory.InventoryCloseEvent.class);
        org.mockito.Mockito.when(event.getPlayer()).thenReturn(other);
        org.mockito.Mockito.when(event.getView()).thenReturn(view);

        this.host.onInventoryClose(event);

        assertEquals(0, holder.closes.get(), "foreign player close must not invalidate the owner's GUI");
    }

    @Test
    void disableClosesOpenGuisAndInvokesCloseHook() {
        final RecordingHolder holder = new RecordingHolder(this.player);
        this.host.openGui(holder);
        this.server.getScheduler().performTicks(1);

        final AtomicInteger disableRuns = new AtomicInteger();
        this.host.setDisableAction((p, g) -> disableRuns.incrementAndGet());
        this.host.onDisable();

        assertEquals(1, holder.closes.get(), "onDisable must invoke close hook");
        assertEquals(1, disableRuns.get(), "disable action must run");
    }

    @Test
    void openPageRoutesGuiClickAndInvalidatesSessionOnClose() {
        final AtomicInteger clicks = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final GuiPage<String> page = new GuiPage<>() {
            @Override
            public Inventory render(final io.pepper.lib.gui.GuiContext context, final String state) {
                return context.newInventory(3, "Page");
            }

            @Override
            public void click(final io.pepper.lib.gui.GuiContext context, final String state, final GuiClick click) {
                clicks.incrementAndGet();
                assertNotNull(context.session(), "session must be live while open");
            }

            @Override
            public void close(final io.pepper.lib.gui.GuiContext context, final String state) {
                closes.incrementAndGet();
                assertNull(context.session(), "session must be invalidated on close");
            }
        };
        this.host.openPage(page, "state", this.player);
        this.server.getScheduler().performTicks(1);

        final InventoryClickEvent event = new InventoryClickEvent(
                this.player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                11,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);
        assertEquals(1, clicks.get());

        this.player.closeInventory();
        assertEquals(1, closes.get());
    }
}
