package ltd.pepper.lib.gui;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 菜单界面分页器。
 *
 * <p>改编自 PluginBase modules/gui（MIT, © 2024 人間工作），
 * 用 {@link Map.Entry}/{@link InventoryView} 替代框架的 Pair/InventoryViewAccessor，
 * 移除调度器依赖，直接调用 Bukkit API。页码计算复用 {@link Pagination}。</p>
 *
 * <p>来源：PepperUnion gui.PageGuide 下沉至 PepperLib（跨插件单一来源）。</p>
 */
public class PageGuide<T> {

    List<Map.Entry<T, ItemStack>> contents = new ArrayList<>();
    List<Integer> slots = new ArrayList<>();
    List<Integer> prevPageSlots = new ArrayList<>();
    List<Integer> nextPageSlots = new ArrayList<>();
    ItemStack btnPrevPage, btnPrevPageCannot, btnNextPage, btnNextPageCannot;
    int maxPage;
    int page = 1;
    Transformer<T> itemTransformer = null;
    /** 页码信息槽位（-1 表示未配置）；每次 {@link #updateInventory} 时按工厂重新生成。 */
    int pageInfoSlot = -1;

    Supplier<ItemStack> pageInfoFactory = null;

    @FunctionalInterface
    public interface Transformer<T> {
        /**
         * 物品修改器
         *
         * @param data         数据
         * @param item         原物品
         * @param slot         格子
         * @param contentIndex 在全部内容中的索引
         * @param pageIndex    在当前页中的索引
         * @param pageGuide    翻页导航实例
         * @return 修改后的物品
         */
        @Nullable
        ItemStack apply(
                T data, @Nullable ItemStack item, int slot, int contentIndex, int pageIndex, PageGuide<T> pageGuide);
    }

    /**
     * 根据格子索引获取图标附带数据
     *
     * @return 图标附带数据。点击位置不属于页面内容，或者该处没有图标时返回 null
     */
    @Nullable
    public T get(int slot) {
        int index = slots.indexOf(slot);
        if (index < 0) return null;
        int i = getStartIndex() + index;
        if (i < 0 || i >= contents.size()) return null;
        return contents.get(i).getKey();
    }

    /**
     * 更新物品栏界面
     */
    public void updateInventory(Inventory inv) {
        updateInventory(inv::setItem);
    }

    /**
     * 更新物品栏界面
     */
    public void updateInventory(InventoryView view) {
        updateInventory(view::setItem);
        HumanEntity player = view.getPlayer();
        if (player instanceof Player) {
            ((Player) player).updateInventory();
        }
    }

    /**
     * 更新物品栏界面
     */
    public void updateInventory(BiConsumer<Integer, ItemStack> setItem) {
        for (int slot : prevPageSlots) {
            setItem.accept(slot, hasPrevPage() ? btnPrevPage : btnPrevPageCannot);
        }
        for (int slot : nextPageSlots) {
            setItem.accept(slot, hasNextPage() ? btnNextPage : btnNextPageCannot);
        }
        int startIndex = getStartIndex();
        for (int i = 0; i < slots.size(); i++) {
            int index = startIndex + i;
            int slot = slots.get(i);
            if (index < contents.size()) {
                Map.Entry<T, ItemStack> entry = contents.get(index);
                ItemStack item = itemTransformer != null
                        ? itemTransformer.apply(entry.getKey(), entry.getValue(), slot, index, i, this)
                        : entry.getValue();
                setItem.accept(slot, item);
            } else {
                setItem.accept(slot, null);
            }
        }
        // 页码信息（第 X / Y 页）随翻页实时重算：翻页只改 page，这个槽位不在
        // 内容区里，必须在这里一并重写，否则页面切换后仍显示"第 1 页"。
        if (pageInfoSlot >= 0 && pageInfoFactory != null) {
            setItem.accept(pageInfoSlot, pageInfoFactory.get());
        }
    }

    /**
     * 注册页码信息槽：每次 {@link #updateInventory} 时按工厂重新生成该槽位的物品
     * （工厂应基于 {@link #getPage()} / {@link #getMaxPage()} 实时构建）。
     */
    public void setPageInfo(int slot, Supplier<ItemStack> factory) {
        this.pageInfoSlot = slot;
        this.pageInfoFactory = factory;
    }

    /**
     * 去事件化的翻页处理（GuiPage 协议形态）：按原始槽位判断翻页并重绘。
     *
     * <p>由调用方提供重绘目标（setItem 回调），不触碰 Bukkit 事件对象；
     * 翻页成功后调用方自行刷新客户端。</p>
     *
     * @param rawSlot 原始槽位（整个视图坐标系）
     * @param setItem 重绘回调（通常为 {@code inventory::setItem}）
     * @return {@code true} 表示翻页已发生（页面已重绘）
     */
    public boolean handlePageSlot(final int rawSlot, final BiConsumer<Integer, ItemStack> setItem) {
        if (slots.contains(rawSlot)) {
            return false;
        }
        if (prevPageSlots.contains(rawSlot) && hasPrevPage()) {
            prevPage();
            updateInventory(setItem);
            return true;
        }
        if (nextPageSlots.contains(rawSlot) && hasNextPage()) {
            nextPage();
            updateInventory(setItem);
            return true;
        }
        return false;
    }

    /**
     * 设置页面内容显示物品转换器
     */
    public void setItemTransformer(Transformer<T> itemTransformer) {
        this.itemTransformer = itemTransformer;
    }

    /**
     * 获取页面内容起始索引
     */
    public PageWindow window() {
        return PageWindow.of(this.page, this.maxPage, this.getPerPageSize());
    }

    public int getStartIndex() {
        return (page - 1) * getPerPageSize();
    }

    /**
     * 获取每一页最多能放多少个图标
     */
    public int getPerPageSize() {
        return slots.size();
    }

    /**
     * 获取最大页数
     */
    public int getMaxPage() {
        return maxPage;
    }

    /**
     * 获取当前页数 (从1开始)
     */
    public int getPage() {
        return page;
    }

    /**
     * 重新计算最大页数。通常这不需要手动调用，进行 add, remove 等等操作之后会执行这个方法。
     */
    public void reCalcMaxPage() {
        // 共享总页数计算（PepperLib Pagination.pageCount）：空内容/空槽位视为 1 页。
        this.maxPage = Pagination.pageCount(this.contents.size(), this.slots.size());
        // 内容收缩后 page 必须收敛回 [1, maxPage]，否则可能停留在一个空页。
        if (this.page > this.maxPage) {
            this.page = this.maxPage;
        }
        if (this.page < 1) {
            this.page = 1;
        }
    }

    /**
     * 是否可以向下翻页
     */
    public boolean hasNextPage() {
        return getPage() < getMaxPage();
    }

    /**
     * 向下翻页，但不会更新页面，请在这之后执行 updateInventory
     *
     * @see PageGuide#updateInventory(Inventory)
     */
    public void nextPage() {
        if (hasNextPage()) page++;
    }

    /**
     * 是否可以向上翻页
     */
    public boolean hasPrevPage() {
        return getPage() > 1;
    }

    /**
     * 向上翻页，但不会更新页面，请在这之后执行 updateInventory
     *
     * @see PageGuide#updateInventory(Inventory)
     */
    public void prevPage() {
        if (hasPrevPage()) page--;
    }

    /**
     * @see PageGuide#setContentSlots(List)
     */
    public void setContentSlots(int... slots) {
        List<Integer> list = new ArrayList<>();
        for (int slot : slots) {
            list.add(slot);
        }
        setContentSlots(list);
    }

    /**
     * 设置物品栏中哪些格子放置页面内容
     */
    public void setContentSlots(List<Integer> slots) {
        this.slots = slots;
        this.reCalcMaxPage();
    }

    /**
     * 添加图标到页面内容
     *
     * @param data    图标附带数据
     * @param display 图标显示物品
     */
    public void add(T data, ItemStack display) {
        contents.add(new AbstractMap.SimpleEntry<>(data, display));
        this.reCalcMaxPage();
    }

    /**
     * 页面中是否有附带特定数据的图标
     */
    public boolean contains(T data) {
        for (Map.Entry<T, ItemStack> content : contents) {
            if (content.getKey().equals(data)) return true;
        }
        return false;
    }

    /**
     * 移除页面中有附带特定数据的图标
     */
    public void remove(T data) {
        contents.removeIf(it -> it.getKey().equals(data));
        this.reCalcMaxPage();
    }

    /**
     * 清空页面图标
     */
    public void clear() {
        contents.clear();
        this.reCalcMaxPage();
    }

    /**
     * 获取页面所有内容
     */
    public List<Map.Entry<T, ItemStack>> getContents() {
        return Collections.unmodifiableList(contents);
    }

    /**
     * 设置向上翻页按钮
     *
     * @param prevPage 可以翻页时显示的图标
     * @param cannot   不能翻页时显示的图标
     * @param slots    图标显示在哪些格子里
     */
    public void setupPrevPageButton(ItemStack prevPage, ItemStack cannot, int... slots) {
        this.btnPrevPage = prevPage;
        this.btnPrevPageCannot = cannot;
        prevPageSlots.clear();
        for (int slot : slots) {
            prevPageSlots.add(slot);
        }
    }

    /**
     * 设置向下翻页按钮
     *
     * @param nextPage 可以翻页时显示的图标
     * @param cannot   不能翻页时显示的图标
     * @param slots    图标显示在哪些格子里
     */
    public void setupNextPageButton(ItemStack nextPage, ItemStack cannot, int... slots) {
        this.btnNextPage = nextPage;
        this.btnNextPageCannot = cannot;
        nextPageSlots.clear();
        for (int slot : slots) {
            nextPageSlots.add(slot);
        }
    }
}
