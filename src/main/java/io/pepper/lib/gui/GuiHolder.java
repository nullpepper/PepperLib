package io.pepper.lib.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单创建与交互接口（统一 PepperUnion {@code IGuiHolder} 与 PepperClaim
 * {@code ClaimGui.Holder} 的事件转发形态）。
 *
 * <p>库存以本接口实现为 holder；{@link GuiHost} 的事件管线经
 * {@code belongsTo}/{@code touchesTop} 守卫后把 Bukkit 点击转换成
 * {@link GuiClick} 语义（8 参 onClick 由实现方决定是否进一步转换）。</p>
 */
public interface GuiHolder extends InventoryHolder {

    /** 正在预览界面的玩家。 */
    Player getPlayer();

    /**
     * 创建物品栏界面。
     *
     * @return 物品栏（holder 必须是本实现，否则 GuiHost 拒绝打开）
     */
    Inventory newInventory();

    /** 是否允许 {@link #newInventory()} 返回 null（返回 null 时不会为玩家打开界面）。 */
    default boolean allowNullInventory() {
        return false;
    }

    /**
     * 界面物品点击时执行（事件已取消；仅收到顶部库存内的格子）。
     *
     * @param action      玩家进行的物品栏操作
     * @param click       玩家进行的点击操作
     * @param slotType    格子类型
     * @param slot        格子索引（原始槽位）
     * @param currentItem 点击的物品
     * @param cursor      点击时指针持有的物品
     * @param view        物品栏界面
     * @param event       点击事件
     */
    void onClick(
            InventoryAction action,
            ClickType click,
            InventoryType.SlotType slotType,
            int slot,
            ItemStack currentItem,
            ItemStack cursor,
            InventoryView view,
            InventoryClickEvent event);

    /** 界面物品拖拽时执行。 */
    default void onDrag(InventoryView view, InventoryDragEvent event) {
        event.setCancelled(true);
    }

    /** 界面关闭时执行。 */
    default void onClose(InventoryView view) {}
}
