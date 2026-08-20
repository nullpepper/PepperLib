package io.pepper.lib.gui;

import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/**
 * GUI 事件边界守卫：只负责事件边界判断，不含业务。
 *
 * <p>统一 PepperUnion {@code GuiEventGuards} 与 PepperClaim 的 Holder/拖拽判断
 * （内部设计文档 extraction-plan §5.6）。实现层不应同时保存 Player 引用和 UUID，
 * 优先使用 UUID 比较。</p>
 */
public final class GuiEventGuards {

    private GuiEventGuards() {}

    /**
     * 持有者是否属于指定玩家。
     *
     * @param holder 库存持有者（通常为 {@link Player}）
     * @param playerId 玩家 UUID
     * @return 持有者为玩家且 UUID 匹配；任一参数无效时为 {@code false}
     */
    public static boolean belongsTo(final InventoryHolder holder, final UUID playerId) {
        if (holder == null || playerId == null) {
            return false;
        }
        return holder instanceof final Player player && playerId.equals(player.getUniqueId());
    }

    /**
     * 原始槽位集合是否触及顶部（业务）库存区域。
     *
     * @param rawSlots 拖拽/点击涉及的原始槽位；{@code null} 视为空
     * @param topSize 顶部库存大小（行数 × 9）
     * @return 任一槽位落在 {@code [0, topSize)} 内为 {@code true}
     */
    public static boolean touchesTop(final Set<Integer> rawSlots, final int topSize) {
        if (rawSlots == null || rawSlots.isEmpty() || topSize <= 0) {
            return false;
        }
        for (final int slot : rawSlots) {
            if (slot >= 0 && slot < topSize) {
                return true;
            }
        }
        return false;
    }
}
