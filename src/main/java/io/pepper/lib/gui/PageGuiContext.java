package io.pepper.lib.gui;

import io.pepper.lib.task.PepperScheduler;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * {@link GuiPage} 桥接上下文：玩家 / 调度器 / 会话标识 + 注入 holder 的库存创建。
 *
 * <p>会话在页面打开期间有效，{@link #invalidate()}（页面关闭时由适配器调用）
 * 后 {@link #session()} 返回 {@code null}——异步回调必须用它校验会话有效性。</p>
 */
public final class PageGuiContext implements GuiContext {

    private final UUID playerId;
    private final PepperScheduler scheduler;
    private volatile InventoryHolder holder;
    private volatile GuiSessionId session;

    public PageGuiContext(final UUID playerId, final PepperScheduler scheduler, final GuiSessionId session) {
        this.playerId = playerId;
        this.scheduler = scheduler;
        this.session = session;
    }

    /** 由适配器注入自身（库存创建时作为 holder）。 */
    public void setHolder(final InventoryHolder holder) {
        this.holder = holder;
    }

    @Override
    public UUID playerId() {
        return this.playerId;
    }

    @Override
    public PepperScheduler scheduler() {
        return this.scheduler;
    }

    @Override
    public GuiSessionId session() {
        return this.session;
    }

    @Override
    public Inventory newInventory(final int rows, final String title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("GUI rows must be 1..6, got " + rows);
        }
        return Bukkit.createInventory(this.holder, rows * 9, title);
    }

    /** 会话失效（页面关闭时调用；异步回调校验 {@link #session()} 为 null 即拒绝）。 */
    public void invalidate() {
        this.session = null;
    }
}
