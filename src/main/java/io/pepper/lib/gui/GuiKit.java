package io.pepper.lib.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 箱子 GUI 物品/文本构造工具。
 *
 * <p>来源：PepperUnion gui.GuiKit 下沉至 PepperLib（跨插件单一来源）。</p>
 */
public final class GuiKit {

    private GuiKit() {}

    /** 顶行装饰玻璃板。 */
    public static ItemStack pane() {
        final ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    /** 带名字与可选 lore 的普通物品。 */
    public static ItemStack namedItem(final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 把 MiniMessage 文本转换为箱子界面标题可用的 legacy 文本。 */
    public static String legacy(final String miniMessage) {
        try {
            return LegacyComponentSerializer.legacySection()
                    .serialize(MiniMessage.miniMessage().deserialize(miniMessage));
        } catch (final RuntimeException malformed) {
            // 语言文件被手改坏时不能阻止菜单打开：回退为纯文本标题。
            return LegacyComponentSerializer.legacySection().serialize(Component.text(miniMessage));
        }
    }

    /** 常见的内容区格子（从 from 起连续 count 个）。 */
    public static int[] contentSlots(final int from, final int count) {
        final int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = from + i;
        }
        return slots;
    }
}
