package io.pepper.lib.world;

import org.bukkit.World;

/**
 * 世界实例句柄：业务 id / Bukkit 世界名 / 模板 id / 活体世界 / 状态。
 *
 * <p><b>线程约束</b>：{@link #world()} 只能在 Bukkit 主线程调用（provider 不保证
 * 其他线程安全）；{@code state()} 任意线程可读。</p>
 *
 * <p><b>生命周期约束</b>：卸载（{@link WorldInstanceState#UNLOADED}/{@link WorldInstanceState#FAILED}）
 * 完成后，句柄上的 {@code World} 引用失效，调用方不得继续持有或使用。</p>
 */
public interface WorldInstance {

    /** @return 业务侧实例 id（创建请求传入） */
    String instanceId();

    /** @return provider 生成的 Bukkit 世界名（全局唯一） */
    String worldName();

    /** @return 模板 id */
    String templateId();

    /**
     * @return 活体 Bukkit 世界；仅 {@link WorldInstanceState#ACTIVE} 且在主线程时可安全使用
     * @throws IllegalStateException 非主线程调用
     */
    World world();

    /** @return 当前状态 */
    WorldInstanceState state();
}
