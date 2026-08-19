package io.pepper.lib.gui;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * 纯数据分页切片：不负责 Bukkit 物品。
 *
 * <p>语义与 PepperClaim {@code ClaimGuiModel} 的 {@code page}/{@code itemAt}
 * 一致（越界返回空列表 / {@code null}），保证插件迁移后 GUI 行为无变化
 * （PepperLib-Extraction-Plan §5.5、阶段 5 退出条件）。</p>
 */
public final class Pagination {

    private Pagination() {}

    /**
     * 取指定窗口的内容切片（不可变副本）。
     *
     * @param items 全部内容；{@code null} 视为空
     * @param window 分页窗口
     * @return 该页内容；窗口越界、空列表或 pageSize 为 0 时返回空列表
     */
    public static <T> List<T> page(final List<T> items, final PageWindow window) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        final int from = window.startIndex();
        if (from >= items.size()) {
            return List.of();
        }
        final int to = Math.min(items.size(), from + window.pageSize());
        return List.copyOf(items.subList(from, to));
    }

    /**
     * 取窗口内指定槽位（0-based，相对窗口起点）的元素。
     *
     * @param items 全部内容；{@code null} 视为空
     * @param window 分页窗口
     * @param slot 槽位（0-based，相对窗口起点）
     * @return 该槽位元素；槽位越界、窗口越界、空列表或 pageSize 为 0 时返回 {@code null}
     */
    @Nullable
    public static <T> T itemAt(final List<T> items, final PageWindow window, final int slot) {
        if (items == null || items.isEmpty() || slot < 0 || window.pageSize() <= 0 || slot >= window.pageSize()) {
            return null;
        }
        final int index = window.startIndex() + slot;
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /**
     * 总页数：向上取整；空内容或非法页容量视为 1 页。
     *
     * <p>与两插件既有语义一致（PepperClaim {@code ClaimGuiModel.pageCount}、
     * PepperUnion {@code PageGuide.reCalcMaxPage}），统一后插件可删除本地实现。</p>
     *
     * @param total 内容总数
     * @param pageSize 每页容量
     * @return 总页数（至少 1）
     */
    public static int pageCount(final int total, final int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }
}
