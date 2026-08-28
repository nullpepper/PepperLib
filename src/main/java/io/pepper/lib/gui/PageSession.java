package io.pepper.lib.gui;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单会话基类（GuiPage 协议形态）：玩家引用、当前库存与渲染上下文，
 * 以及异步回调所需的守卫（菜单仍打开）与回主线程辅助。
 *
 * <p>来源：PepperUnion gui.PageSession 与 PepperPvpArena gui.PvpPageSession
 * 合并下沉（含异步槽位刷新 {@link #refreshSlot} 与安全发消息 {@link #send}）。</p>
 */
public abstract class PageSession {

    private final UUID playerId;
    private volatile Inventory inventory;
    private volatile GuiContext context;

    protected PageSession(final Player player) {
        this.playerId = player.getUniqueId();
    }

    /** 当前玩家；离线返回 {@code null}。 */
    public Player player() {
        return Bukkit.getPlayer(this.playerId);
    }

    /** 当前打开的库存是否为本会话的库存（异步回调守卫）。 */
    public boolean isCurrent(final Inventory inv) {
        return this.inventory != null && this.inventory == inv;
    }

    public void setInventory(final Inventory inventory) {
        this.inventory = inventory;
    }

    /** 当前打开的库存（异步回调守卫经 {@link #isCurrent}）。 */
    public Inventory inventory() {
        return this.inventory;
    }

    public void setContext(final GuiContext context) {
        this.context = context;
    }

    /**
     * 皮肤/服务异步就绪后的安全单槽重写：仅当本菜单仍处于打开状态时
     * 写入槽位并刷新客户端（setItem 会克隆 ItemStack，因此必须重写
     * 槽位后再 updateInventory，否则客户端只会重发旧物品）。
     */
    public void refreshSlot(final int slot, final ItemStack item) {
        final Player p = player();
        if (p == null || !p.isOnline() || !isCurrent(p.getOpenInventory().getTopInventory())) {
            return;
        }
        if (slot < 0 || slot >= this.inventory.getSize() || item == null) {
            return;
        }
        if (item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        this.inventory.setItem(slot, item);
        p.updateInventory();
    }

    /** 在 Minecraft 主线程上执行动作（异步服务回调中访问 Bukkit API 必须经过）。 */
    public void runOnMain(final Runnable action) {
        final GuiContext ctx = this.context;
        if (ctx != null) {
            ctx.scheduler().runTask(action);
        }
    }

    /** 会话内安全发送消息（玩家离线时静默丢弃）。 */
    public void send(final Component message) {
        final Player p = player();
        if (p != null && p.isOnline()) {
            p.sendMessage(message);
        }
    }
}
