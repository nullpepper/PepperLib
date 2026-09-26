package ltd.pepper.lib.dialog;

import org.bukkit.entity.Player;

/**
 * 对话框按钮的点击回调：携带该按钮装配时绑定的业务值（例如“点了哪个称号”）。
 *
 * <p><b>线程模型</b>：由 {@link DialogHost} 统一调度到 Minecraft 主线程执行；调用方不需要
 * 自行切换线程，也不应假设自己会在哪个线程被调用。</p>
 *
 * <p><b>异常契约</b>：回调抛出的任何异常（含 {@link Error}）都被 {@link DialogHost} 捕获并
 * 记入插件日志，不会冒泡到服务端的对话框处理路径。回调因此不必为了“不炸服务端”而退化为
 * 空 catch——但也不应吞掉可诊断信息。</p>
 *
 * @param <V> 业务值类型（同一对话框内各按钮一致）
 */
@FunctionalInterface
public interface DialogClick<V> {

    /**
     * @param value 该按钮装配时绑定的业务值（可为 {@code null}）
     * @param player 点击者。服务端回调传入的 audience 必须是 {@link Player}；否则该次点击被
     *     丢弃并记日志，本方法不会被调用（因此实现里可以假定 {@code player} 非 null）
     */
    void onClick(V value, Player player);
}
