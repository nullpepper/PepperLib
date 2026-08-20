package io.pepper.lib.task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 主线程 / 异步调度抽象：业务代码不直接 import Bukkit 调度 API。
 *
 * <p>规范名（{@code runTask / runAsync / runRepeating / supplyOnMain / isMainThread}）为
 * 抽象契约；遗留别名（{@code runTaskAsynchronously / runTaskTimer / supplyOnMainThread}）
 * 以 default 委托规范名，存量调用名不受影响。生产实现见 {@link BukkitPepperScheduler}。</p>
 */
public interface PepperScheduler {

    /**
     * 当前调用线程是否为 Minecraft 主线程。
     *
     * <p>测试环境中的立即调度器在调用线程执行一切，因此调用线程就是其
     * “主线程”；生产实现必须按 {@code Bukkit.isPrimaryThread()} 返回真实结果。</p>
     */
    boolean isMainThread();

    /** 在 Minecraft 主线程上运行任务。 */
    void runTask(Runnable task);

    /** 在后台线程上运行任务。 */
    void runAsync(Runnable task);

    /** 以固定周期在主线程上运行任务（首次执行在 delayTicks 之后）。 */
    void runRepeating(Runnable task, long delayTicks, long periodTicks);

    /**
     * 在 Minecraft 主线程上运行 supplier，并将其结果完成返回的 future。
     *
     * <p>用于原本异步的链中需要主线程执行的经济调用等场景。</p>
     */
    <T> CompletableFuture<T> supplyOnMain(Supplier<T> supplier);

    /** 遗留别名：{@link #runAsync}（存量调用名兼容）。 */
    default void runTaskAsynchronously(final Runnable task) {
        this.runAsync(task);
    }

    /** 遗留别名：{@link #runRepeating}（存量调用名兼容）。 */
    default void runTaskTimer(final Runnable task, final long delayTicks, final long periodTicks) {
        this.runRepeating(task, delayTicks, periodTicks);
    }

    /** 遗留别名：{@link #supplyOnMain}（存量调用名兼容）。 */
    default <T> CompletableFuture<T> supplyOnMainThread(final Supplier<T> supplier) {
        return this.supplyOnMain(supplier);
    }
}
