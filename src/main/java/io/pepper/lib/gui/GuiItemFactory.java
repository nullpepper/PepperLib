package io.pepper.lib.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 最小物品构造接口：业务页面用它构造展示物品，不直接拼装 Bukkit 元数据。
 *
 * <p><b>Experimental（0.1.0）</b>：尚无插件消费者，API 可能在 0.2.x 调整；
 * 两插件菜单迁移到 GuiPage 协议时渐进接入（计划 §4.1 清单项）。</p>
 *
 * @see <a href="https://jd.papermc.io/paper/1.21/">Paper API</a>
 */
@FunctionalInterface
public interface GuiItemFactory {

    /**
     * 构造一个展示物品。
     *
     * @param material 材质
     * @param displayName 显示名；{@code null} 表示不设置
     * @param lore 描述行；{@code null} 表示不设置
     * @return 构造好的物品
     */
    ItemStack create(Material material, @Nullable Component displayName, @Nullable List<Component> lore);
}
