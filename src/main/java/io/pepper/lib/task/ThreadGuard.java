package io.pepper.lib.task;

/**
 * 线程纪律守卫（开发期/测试期）：暴露违反线程纪律的路径。
 *
 * <p>统一 PepperClaim 与 PepperUnion 的实现（两插件核心逐字相同，本类为超集）：
 * <ul>
 *   <li><b>Async 阶段断言</b>：{@code enterAsync}/{@code exitAsync} 标记异步阶段，
 *   启用守卫时在 Async 阶段调用主线程等待（{@link #assertNotAsync}）立即抛错；</li>
 *   <li><b>主线程 IO 断言</b>：{@code enterMainThread}/{@code exitMainThread} 标记
 *   主线程 tick 回调，启用守卫时在主线程标记内做 Storage/文件 IO
 *   （{@link #assertMainThreadIo}）立即抛错。</li>
 * </ul>
 *
 * <p>默认关闭以兼容独立测试初始化；插件 {@code onEnable} 与测试夹具强制开启。</p>
 */
public final class ThreadGuard {

    private static final ThreadLocal<Boolean> IN_ASYNC = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_MAIN_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile boolean enforcementEnabled;

    private ThreadGuard() {}

    public static void setEnforcementEnabled(final boolean enabled) {
        enforcementEnabled = enabled;
    }

    public static boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public static boolean isInAsync() {
        return Boolean.TRUE.equals(IN_ASYNC.get());
    }

    public static void enterAsync() {
        IN_ASYNC.set(Boolean.TRUE);
    }

    public static void exitAsync() {
        IN_ASYNC.remove();
    }

    /** 在 Async 阶段调用主线程等待时应先调用此方法；启用守卫时抛错。 */
    public static void assertNotAsync() {
        assertNotAsync("Async phase must not block on the Minecraft main thread");
    }

    /** 在 Async 阶段执行指定动作时应先调用此方法；启用守卫时抛出带上下文的错误。 */
    public static void assertNotAsync(final String action) {
        if (enforcementEnabled && isInAsync()) {
            throw new IllegalStateException(action);
        }
    }

    /** 主线程 tick 回调入口标记（维护 tick 包装；强制开启时主线程 Storage/文件 IO 即炸）。 */
    public static void enterMainThread() {
        IN_MAIN_THREAD.set(Boolean.TRUE);
    }

    public static void exitMainThread() {
        IN_MAIN_THREAD.remove();
    }

    public static boolean isMainThread() {
        return Boolean.TRUE.equals(IN_MAIN_THREAD.get());
    }

    /** 主线程 IO 断言：强制开启且处于主线程标记 → 抛错（拒绝主线程 Storage/文件 IO）。 */
    public static void assertMainThreadIo() {
        if (enforcementEnabled && isMainThread()) {
            throw new IllegalStateException("Storage/file IO must not run on the Minecraft main thread");
        }
    }
}
