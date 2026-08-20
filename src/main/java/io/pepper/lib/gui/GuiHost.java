package io.pepper.lib.gui;

import io.pepper.lib.task.PepperScheduler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;

/**
 * 箱子 GUI 事件管线与生命周期（统一 PepperUnion {@code GuiManager} 与
 * PepperClaim {@code ClaimGuiListener}）：打开调度（下一 tick + 防连点）、
 * 事件守卫转发（{@link GuiEventGuards} + {@link GuiClick}）、禁用清理。
 *
 * <p><b>实例类</b>（非静态单例）：PepperClaim 与 PepperUnion 可同服共存，
 * 各自持有独立实例；插件把实例交给本插件的静态入口（如 GuiManager 壳）或
 * 组合根字段。</p>
 *
 * <p>改编自 PluginBase modules/gui（MIT, © 2024 人間工作）。</p>
 */
public final class GuiHost implements Listener {

    private final Plugin plugin;
    private final PepperScheduler scheduler;
    private BiConsumer<Player, GuiHolder> disable = (player, gui) -> {};
    private boolean disabled = false;

    /** 每个玩家最新一次待打开的界面：连点导航时只让最后一次打开请求生效。 */
    private final Map<UUID, GuiHolder> pendingOpens = new ConcurrentHashMap<>();

    /**
     * @param plugin 插件（事件注册与打开调度用）
     * @param scheduler 页面上下文调度器（{@link #openPage} 的 GuiContext 使用）
     */
    public GuiHost(final Plugin plugin, final PepperScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** 打开一个 {@link GuiHolder} 界面（下一 tick 调度 + 连点防抖）。 */
    public void openGui(final GuiHolder gui) {
        if (disabled) {
            return;
        }
        final Player player = gui.getPlayer();
        if (player == null) {
            return;
        }
        final Inventory inv = gui.newInventory();
        if (inv != null) {
            if (inv.getHolder() == gui) {
                this.pendingOpens.put(player.getUniqueId(), gui);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    // 调度到下一 tick 时玩家可能已退出（如确认菜单的取消回调在
                    // 玩家退出瞬间触发）：对离线玩家 openInventory 会抛异常。
                    if (!player.isOnline()) {
                        return;
                    }
                    // 连点导航会连续调度多个打开任务：只放行最新一次请求，
                    // 避免旧任务随后打开陈旧界面覆盖玩家当前菜单。
                    // 双参 CAS：只有"当前待打开项仍是自己"才移除并打开。
                    // 若已被更新的 openGui 请求覆盖，保留新记录并放弃本次打开，
                    // 避免旧任务把最新请求的 pending 记录误删。
                    if (!this.pendingOpens.remove(player.getUniqueId(), gui)) {
                        return;
                    }
                    player.openInventory(inv);
                });
            } else {
                this.plugin
                        .getLogger()
                        .warning("试图为玩家 " + player.getName() + " 打开界面 "
                                + gui.getClass().getName() + " 时，界面未设置 InventoryHolder 为自身实例");
            }
        } else if (!gui.allowNullInventory()) {
            this.plugin
                    .getLogger()
                    .warning("试图为玩家 " + player.getName() + " 打开界面 "
                            + gui.getClass().getName() + " 时，程序返回了 null");
        }
    }

    /**
     * 打开一个 {@link GuiPage} 页面（协议层入口）。
     *
     * <p>生成新的 {@link GuiSessionId}（页面关闭时失效），经
     * {@link PageHolderAdapter} 桥接进 {@link #openGui} 事件管线；
     * 页面点击统一转换为去 Bukkit 化的 {@link GuiClick}。</p>
     */
    public <S> void openPage(final GuiPage<S> page, final S state, final Player player) {
        if (disabled || player == null) {
            return;
        }
        final GuiSessionId session = new GuiSessionId(player.getUniqueId(), UUID.randomUUID(), 0L);
        final PageGuiContext context = new PageGuiContext(player.getUniqueId(), this.scheduler, session);
        final PageHolderAdapter<S> adapter = new PageHolderAdapter<>(page, state);
        adapter.setContext(context);
        openGui(adapter);
    }

    /** 插件禁用（onDisable）：关闭全部本插件打开的界面并触发关闭钩子。 */
    public void onDisable() {
        disabled = true;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final GuiHolder gui = getInventoryHolder(player.getOpenInventory().getTopInventory());
            if (gui != null) {
                gui.onClose(player.getOpenInventory());
                // 同步关闭界面：调度到下一 tick 的任务会在 onDisable 返回后被
                // Bukkit 的 cancelTasks 取消，永远不会执行；而界面不关闭的话，
                // disabled 期间玩家仍可对打开的界面进行未取消的点击（搬出/搬入物品）。
                try {
                    player.closeInventory();
                } catch (final Throwable ignored) {
                    // 关闭失败时不再尝试调度（调度器在禁用期间同样不可用）。
                }
                this.disable.accept(player, gui);
            }
        }
    }

    /** 注入插件禁用提示动作（默认为空；PepperUnion 注入标题提示）。 */
    public void setDisableAction(final BiConsumer<Player, GuiHolder> consumer) {
        this.disable = consumer;
    }

    /** 玩家当前打开的界面（无则 null）。 */
    public GuiHolder getOpeningGui(final Player player) {
        if (disabled) {
            return null;
        }
        return getInventoryHolder(player.getOpenInventory().getTopInventory());
    }

    /** 库存持有者是否为 {@link GuiHolder}。 */
    public GuiHolder getInventoryHolder(final Inventory inv) {
        final InventoryHolder holder = inv.getHolder();
        return holder instanceof GuiHolder gui ? gui : null;
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent e) {
        this.pendingOpens.remove(e.getPlayer().getUniqueId());
        if (disabled) {
            return;
        }
        // 玩家退出时界面随玩家一并销毁，这里不调用 onClose：
        // onClose 对确认菜单等同"取消"，会触发"返回上一菜单"等取消回调，
        // 试图为已退出的玩家打开新界面。清理工作由各界面自行兜底。
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        final InventoryView view = event.getView();
        final GuiHolder gui = getInventoryHolder(view.getTopInventory());
        // 仅转发顶部容器点击；玩家自己的背包（底部）不要进入菜单逻辑。
        // 归属校验走共享守卫（GuiEventGuards：UUID 比较）；gui 为 null（普通箱子/
        // 其他插件界面）时直接放行，不进入本插件菜单逻辑。
        if (gui != null
                && GuiEventGuards.belongsTo(gui.getPlayer(), ((Player) event.getWhoClicked()).getUniqueId())
                && event.getClickedInventory() == view.getTopInventory()) {
            // 先取消再判断 disabled：插件禁用/重载期间打开的界面可能没有被关闭，
            // 不能放任玩家对界面物品进行搬移（移出=免费获得物品，移入=物品丢失）。
            // 其他插件/普通箱子的界面不受影响。
            event.setCancelled(true);
            if (disabled) {
                return;
            }
            gui.onClick(
                    event.getAction(),
                    event.getClick(),
                    event.getSlotType(),
                    event.getRawSlot(),
                    event.getCurrentItem(),
                    event.getCursor(),
                    view,
                    event);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        final InventoryView view = event.getView();
        final GuiHolder gui = getInventoryHolder(view.getTopInventory());
        if (gui != null
                && GuiEventGuards.belongsTo(gui.getPlayer(), ((Player) event.getWhoClicked()).getUniqueId())
                && GuiEventGuards.touchesTop(
                        event.getRawSlots(), view.getTopInventory().getSize())) {
            // 与点击一致：本插件界面先取消，disabled 期间直接忽略。
            event.setCancelled(true);
            if (disabled) {
                return;
            }
            gui.onDrag(view, event);
        }
    }

    @EventHandler
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (disabled || !(event.getPlayer() instanceof Player)) {
            return;
        }
        final GuiHolder gui = getInventoryHolder(event.getView().getTopInventory());
        if (gui != null) {
            gui.onClose(event.getView());
        }
    }
}
