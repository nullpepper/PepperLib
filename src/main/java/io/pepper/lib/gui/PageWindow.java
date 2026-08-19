package io.pepper.lib.gui;

/**
 * 不可变的一页分页窗口（1-based）。
 *
 * <p>页码从 1 开始；{@code startIndex} 为该页第一个元素在全部内容中的
 * 0-based 索引。{@link #of} 对越界输入做收敛（clamp），直接构造则严格校验。</p>
 *
 * <p>统一 PepperUnion {@code PageWindow} 与 PepperClaim {@code GuiPageBounds}
 * 到该契约（PepperLib-Extraction-Plan §5.4）。</p>
 */
public record PageWindow(int page, int maxPage, int pageSize, int startIndex) {

    /**
     * 构造分页窗口。
     *
     * @param page 页码（1-based）
     * @param maxPage 最大页数
     * @param pageSize 每页容量（允许 0）
     * @param startIndex 本页起始元素索引（0-based）
     * @throws IllegalArgumentException 页码/页数/页容量/起始索引不一致
     */
    public PageWindow {
        if (maxPage < 1) {
            throw new IllegalArgumentException("maxPage must be >= 1, got " + maxPage);
        }
        if (pageSize < 0) {
            throw new IllegalArgumentException("pageSize must be >= 0, got " + pageSize);
        }
        if (page < 1 || page > maxPage) {
            throw new IllegalArgumentException("page must be in [1, " + maxPage + "], got " + page);
        }
        if (startIndex != (page - 1) * pageSize) {
            throw new IllegalArgumentException(
                    "startIndex must equal (page - 1) * pageSize = " + ((page - 1) * pageSize) + ", got " + startIndex);
        }
    }

    /**
     * 创建分页窗口：越界输入收敛到合法区间。
     *
     * <ul>
     *   <li>{@code maxPage < 1} 收敛为 1；</li>
     *   <li>{@code page < 1} 收敛为 1，{@code page > maxPage} 收敛为 {@code maxPage}；</li>
     *   <li>{@code pageSize < 0} 收敛为 0。</li>
     * </ul>
     */
    public static PageWindow of(final int page, final int maxPage, final int pageSize) {
        final int safeMax = Math.max(1, maxPage);
        final int safeSize = Math.max(0, pageSize);
        final int safePage = Math.max(1, Math.min(page, safeMax));
        return new PageWindow(safePage, safeMax, safeSize, (safePage - 1) * safeSize);
    }

    /** 是否有上一页。 */
    public boolean hasPrevious() {
        return this.page > 1;
    }

    /** 是否有下一页。 */
    public boolean hasNext() {
        return this.page < this.maxPage;
    }

    /**
     * 从 0-based 页码创建窗口（插件适配层换算入口）。
     *
     * <p>PepperClaim 的 Holder 内部使用 0-based {@code pageIndex}；本工厂封装
     * 0-based → 1-based 换算（{@code of(page + 1, maxPage, pageSize)}），
     * 越界行为与 {@link #of} 一致（clamp）。</p>
     *
     * @param page 0-based 页码
     * @param maxPage 总页数（1-based，即 {@code Pagination.pageCount} 的结果）
     * @param pageSize 每页容量
     */
    public static PageWindow fromZeroBased(final int page, final int maxPage, final int pageSize) {
        return of(page + 1, maxPage, pageSize);
    }
}
