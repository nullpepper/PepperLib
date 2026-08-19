package io.pepper.lib.task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 主线程 / 异步调度抽象：业务代码不直接 import Bukkit 调度 API。
 *
 * <p>本库只定义契约，不提供 Bukkit 实现——由插件各自实现适配器
 * （PepperUnion {@code PaperScheduler}、PepperClaim {@code PaperScheduler} 适配本接口）。</p>
 *
 * <p>命名与两插件现有 {@code Scheduler} 的映射：{@code runAsync} ←
 * {@code runTaskAsynchronously}；{@code runRepeating} ← {@code runTaskTimer}；
 * {@code supplyOnMain} ← {@code supplyOnMainThread}。</p>
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
}
