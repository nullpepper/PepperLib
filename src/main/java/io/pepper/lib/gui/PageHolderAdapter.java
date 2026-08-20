package io.pepper.lib.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * {@link GuiPage} → {@link GuiHolder} 适配器：事件转换（Bukkit 点击 →
 * {@link GuiClick}）与会话生命周期（打开生成 / 关闭失效）。
 *
 * <p>页面经 {@link PageGuiContext#newInventory} 创建的库存以本适配器为 holder，
 * {@link GuiHost} 的事件转发因此能回到本适配器并转成去 Bukkit 化的页面点击。</p>
 *
 * @param <S> 页面状态类型
 */
public final class PageHolderAdapter<S> implements GuiHolder {

    private final GuiPage<S> page;
    private final S state;
    private volatile PageGuiContext context;
    private Inventory inventory;

    public PageHolderAdapter(final GuiPage<S> page, final S state) {
        this.page = page;
        this.state = state;
    }

    /** 注入上下文（构造完成后由 GuiHost 调用）。 */
    public void setContext(final PageGuiContext context) {
        this.context = context;
        context.setHolder(this);
    }

    @Override
    public Player getPlayer() {
        return Bukkit.getPlayer(this.context.playerId());
    }

    @Override
    public Inventory newInventory() {
        this.inventory = this.page.render(this.context, this.state);
        if (this.inventory != null && this.inventory.getHolder() != this) {
            throw new IllegalStateException("GuiPage must create its inventory via GuiContext.newInventory "
                    + "(holder must be the page adapter), got "
                    + this.inventory.getHolder());
        }
        return this.inventory;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    @Override
    public boolean allowNullInventory() {
        return true;
    }

    @Override
    public void onClick(
            final InventoryAction action,
            final ClickType click,
            final InventoryType.SlotType slotType,
            final int slot,
            final ItemStack currentItem,
            final ItemStack cursor,
            final InventoryView view,
            final InventoryClickEvent event) {
        event.setCancelled(true);
        if (this.inventory == null || slot < 0 || slot >= this.inventory.getSize()) {
            return;
        }
        this.page.click(this.context, this.state, new GuiClick(slot, click, action, true));
    }

    @Override
    public void onDrag(final InventoryView view, final InventoryDragEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onClose(final InventoryView view) {
        this.context.invalidate();
        this.page.close(this.context, this.state);
    }
}
