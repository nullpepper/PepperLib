package io.pepper.lib.task;

/**
 * 线程纪律守卫（开发期/测试期）：暴露违反线程纪律的路径。
 *
 * <p>统一 PepperClaim 与 PepperUnion 的实现（两插件核心逐字相同，本类为超集）：
 * <ul>
 *   <li><b>Async 阶段断言</b>：{@code enterAsync}/{@code exitAsync} 标记异步阶段，
 *   启用守卫时在 Async 阶段调用主线程等待（{@link Instance#assertNotAsync()}）立即抛错；</li>
 *   <li><b>主线程 IO 断言</b>：{@code enterMainThread}/{@code exitMainThread} 标记
 *   主线程 tick 回调，启用守卫时在主线程标记内做 Storage/文件 IO
 *   （{@link Instance#assertMainThreadIo()}）立即抛错。</li>
 * </ul>
 *
 * <p><b>实例化（{@link Instance}）</b>：lib 由服务器单一实例提供（两插件 compileOnly、
 * 不 shade），静态状态会跨插件共享/污染；每个插件应在组合根持有自己的
 * {@code ThreadGuard.Instance}（插件类加载器隔离），新代码一律使用实例 API。</p>
 *
 * <p>本类的静态入口为 0.1.x 兼容壳（{@link Deprecated}），委托到进程级默认实例；
 * 插件存量调用点与测试无需改动。</p>
 *
 * <p>默认关闭以兼容独立测试初始化；插件 {@code onEnable} 与测试夹具强制开启。</p>
 */
public final class ThreadGuard {

    private ThreadGuard() {}

    /** 进程级默认实例：仅供存量静态调用委托；插件新代码应使用各自组合根的实例。 */
    private static final Instance DEFAULT = new Instance();

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void setEnforcementEnabled(final boolean enabled) {
        DEFAULT.setEnforcementEnabled(enabled);
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static boolean isEnforcementEnabled() {
        return DEFAULT.isEnforcementEnabled();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static boolean isInAsync() {
        return DEFAULT.isInAsync();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void enterAsync() {
        DEFAULT.enterAsync();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void exitAsync() {
        DEFAULT.exitAsync();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void assertNotAsync() {
        DEFAULT.assertNotAsync();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void assertNotAsync(final String action) {
        DEFAULT.assertNotAsync(action);
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void enterMainThread() {
        DEFAULT.enterMainThread();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void exitMainThread() {
        DEFAULT.exitMainThread();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static boolean isMainThread() {
        return DEFAULT.isMainThread();
    }

    /** @deprecated 静态壳委托进程级默认实例；新代码使用 {@link Instance}。 */
    @Deprecated
    public static void assertMainThreadIo() {
        DEFAULT.assertMainThreadIo();
    }

    /**
     * 独立守卫实例（每个插件组合根一个）。状态（强制开关 + 线程阶段标记）全部
     * 实例级：同服共享 lib 类加载时互不干扰。
     */
    public static final class Instance {

        private final ThreadLocal<Boolean> inAsync = ThreadLocal.withInitial(() -> Boolean.FALSE);
        private final ThreadLocal<Boolean> inMainThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
        private volatile boolean enforcementEnabled;

        /** 创建独立守卫实例。 */
        public Instance() {}

        /** 启用/停用本实例的纪律强制。 */
        public void setEnforcementEnabled(final boolean enabled) {
            this.enforcementEnabled = enabled;
        }

        public boolean isEnforcementEnabled() {
            return this.enforcementEnabled;
        }

        public boolean isInAsync() {
            return Boolean.TRUE.equals(this.inAsync.get());
        }

        public void enterAsync() {
            this.inAsync.set(Boolean.TRUE);
        }

        public void exitAsync() {
            this.inAsync.remove();
        }

        /** 在 Async 阶段调用主线程等待时应先调用此方法；启用守卫时抛错。 */
        public void assertNotAsync() {
            this.assertNotAsync("Async phase must not block on the Minecraft main thread");
        }

        /** 在 Async 阶段执行指定动作时应先调用此方法；启用守卫时抛出带上下文的错误。 */
        public void assertNotAsync(final String action) {
            if (this.enforcementEnabled && this.isInAsync()) {
                throw new IllegalStateException(action);
            }
        }

        /** 主线程 tick 回调入口标记（维护 tick 包装；强制开启时主线程 Storage/文件 IO 即炸）。 */
        public void enterMainThread() {
            this.inMainThread.set(Boolean.TRUE);
        }

        public void exitMainThread() {
            this.inMainThread.remove();
        }

        public boolean isMainThread() {
            return Boolean.TRUE.equals(this.inMainThread.get());
        }

        /** 主线程 IO 断言：强制开启且处于主线程标记 → 抛错（拒绝主线程 Storage/文件 IO）。 */
        public void assertMainThreadIo() {
            if (this.enforcementEnabled && this.isMainThread()) {
                throw new IllegalStateException("Storage/file IO must not run on the Minecraft main thread");
            }
        }
    }
}
