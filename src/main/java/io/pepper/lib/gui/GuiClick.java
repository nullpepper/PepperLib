package io.pepper.lib.gui;

import io.pepper.lib.validation.Preconditions;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

/**
 * 去 Bukkit 化的点击协议：业务页面不直接依赖完整事件对象。
 *
 * <p>由插件的 GuiManager / Listener 负责把 Bukkit 事件转换成该记录
 * （内部设计文档 extraction-plan §5.1）。</p>
 */
public record GuiClick(int rawSlot, ClickType clickType, InventoryAction action, boolean topInventory) {

    /**
     * 构造点击协议。
     *
     * @param rawSlot 原始槽位（整个视图坐标系，非负）
     * @param clickType 点击类型
     * @param action 库存动作
     * @param topInventory 是否点击在顶部（业务）库存
     */
    public GuiClick {
        Preconditions.requireNonNull(clickType, "clickType");
        Preconditions.requireNonNull(action, "action");
        if (rawSlot < 0) {
            throw new IllegalArgumentException("rawSlot must be >= 0, got " + rawSlot);
        }
    }
}
