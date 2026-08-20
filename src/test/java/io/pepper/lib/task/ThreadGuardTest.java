package io.pepper.lib.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ThreadGuard} 契约测试（统一 PepperClaim/PepperUnion 线程纪律守卫）：
 * Async 阶段断言、带上下文重载、主线程标记 IO 断言、未开启不误伤、实例间隔离、
 * 兼容静态壳委托。
 */
class ThreadGuardTest {

    private final ThreadGuard.Instance guard = new ThreadGuard.Instance();

    @AfterEach
    void tearDown() {
        this.guard.exitAsync();
        this.guard.exitMainThread();
        this.guard.setEnforcementEnabled(false);
        // 静态壳测试可能已触碰进程级默认实例：一并复位，避免污染其他测试。
        ThreadGuard.exitAsync();
        ThreadGuard.exitMainThread();
        ThreadGuard.setEnforcementEnabled(false);
    }

    @Test
    void asyncAssertionFiresWhenEnforcedInsideAsync() {
        this.guard.setEnforcementEnabled(true);
        this.guard.enterAsync();
        assertThrows(IllegalStateException.class, () -> this.guard.assertNotAsync());
    }

    @Test
    void asyncAssertionWithContextCarriesMessage() {
        this.guard.setEnforcementEnabled(true);
        this.guard.enterAsync();
        final IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> this.guard.assertNotAsync("write forbidden"));
        assertTrue(e.getMessage().contains("write forbidden"));
    }

    @Test
    void asyncAssertionPassesOutsideAsyncOrWhenNotEnforced() {
        this.guard.setEnforcementEnabled(true);
        assertDoesNotThrow(() -> this.guard.assertNotAsync());

        this.guard.setEnforcementEnabled(false);
        this.guard.enterAsync();
        assertDoesNotThrow(() -> this.guard.assertNotAsync());
    }

    @Test
    void mainThreadIoAssertionFiresWhenEnforcedOnMainThread() {
        this.guard.setEnforcementEnabled(true);
        this.guard.enterMainThread();
        assertThrows(IllegalStateException.class, () -> this.guard.assertMainThreadIo());
    }

    @Test
    void mainThreadIoAssertionPassesOffMainThreadOrWhenNotEnforced() {
        this.guard.setEnforcementEnabled(true);
        assertDoesNotThrow(() -> this.guard.assertMainThreadIo());

        this.guard.setEnforcementEnabled(false);
        this.guard.enterMainThread();
        assertDoesNotThrow(() -> this.guard.assertMainThreadIo());
    }

    @Test
    void stateFlagsReflectCurrentPhase() {
        assertFalse(this.guard.isInAsync());
        assertFalse(this.guard.isMainThread());

        this.guard.enterAsync();
        assertTrue(this.guard.isInAsync());
        this.guard.enterMainThread();
        assertTrue(this.guard.isMainThread());
    }

    @Test
    void nestedAsyncScopesRemainArmedUntilOutermostExit() {
        this.guard.setEnforcementEnabled(true);
        this.guard.enterAsync();
        this.guard.enterAsync();

        this.guard.exitAsync();
        assertTrue(this.guard.isInAsync());
        assertThrows(IllegalStateException.class, () -> this.guard.assertNotAsync());

        this.guard.exitAsync();
        assertFalse(this.guard.isInAsync());
    }

    @Test
    void nestedMainThreadScopesRemainArmedUntilOutermostExit() {
        this.guard.setEnforcementEnabled(true);
        this.guard.enterMainThread();
        this.guard.enterMainThread();

        this.guard.exitMainThread();
        assertTrue(this.guard.isMainThread());
        assertThrows(IllegalStateException.class, () -> this.guard.assertMainThreadIo());

        this.guard.exitMainThread();
        assertFalse(this.guard.isMainThread());
    }

    @Test
    void instancesAreIsolatedFromEachOther() {
        final ThreadGuard.Instance other = new ThreadGuard.Instance();
        // 两插件各持独立实例：A 的强制/标记不得影响 B（共享类加载场景下的核心属性）。
        this.guard.setEnforcementEnabled(true);
        this.guard.enterAsync();
        this.guard.enterMainThread();

        assertDoesNotThrow(() -> other.assertNotAsync());
        assertDoesNotThrow(() -> other.assertMainThreadIo());
        assertFalse(other.isInAsync());
        assertFalse(other.isMainThread());
        assertFalse(other.isEnforcementEnabled());

        assertTrue(this.guard.isInAsync());
        assertTrue(this.guard.isMainThread());
    }

    @Test
    void legacyStaticShellDelegatesToDefaultInstance() {
        // 0.1.x 兼容壳：存量静态调用点（含插件测试）继续可用，语义与实例一致。
        ThreadGuard.setEnforcementEnabled(true);
        ThreadGuard.enterAsync();
        assertThrows(IllegalStateException.class, () -> ThreadGuard.assertNotAsync());
        ThreadGuard.enterMainThread();
        assertThrows(IllegalStateException.class, () -> ThreadGuard.assertMainThreadIo());
    }
}
