package io.pepper.lib.gui;

import io.pepper.lib.task.PepperScheduler;
import java.util.UUID;

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
}
