package io.pepper.lib.gui;

import io.pepper.lib.task.PepperScheduler;
import java.util.UUID;
import org.bukkit.inventory.Inventory;

/**
 * 页面渲染上下文：只提供玩家、调度器、session 与插件适配数据，
 * 不直接暴露业务服务集合（PepperLib-Extraction-Plan §5.3）。
 */
public interface GuiContext {

    /** 当前玩家 UUID。 */
    UUID playerId();

    /** 调度器（回主线程 / 异步）。 */
    PepperScheduler scheduler();

    /** 本次打开的会话标识（异步回调有效性校验用）。 */
    GuiSessionId session();

    /**
     * 创建页面库存：holder 由 GuiManager 注入（事件转发与归属校验依赖
     * holder 是页面适配器实例），页面渲染必须经此方法创建库存。
     *
     * @param rows 行数（1-6）
     * @param title 库存标题（legacy 文本）
     * @return holder 已注入的库存
     */
    Inventory newInventory(int rows, String title);
}
