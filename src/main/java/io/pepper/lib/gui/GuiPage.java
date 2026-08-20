package io.pepper.lib.gui;

import org.bukkit.inventory.Inventory;

/**
 * 最小页面协议：第一版不引入复杂 sealed action 状态机。
 *
 * <p>业务页面由插件实现本接口；完整的 GuiManager / 菜单生命周期留在插件内
 * （内部设计文档 extraction-plan §5.3）。</p>
 *
 * @param <S> 页面状态类型
 */
public interface GuiPage<S> {

    /** 渲染页面。 */
    Inventory render(GuiContext context, S state);

    /** 处理一次去 Bukkit 化的点击。 */
    void click(GuiContext context, S state, GuiClick click);

    /** 页面关闭钩子（默认空操作）。 */
    default void close(GuiContext context, S state) {}
}
