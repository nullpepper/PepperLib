package io.pepper.lib.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ThreadGuard} 契约测试（统一 PepperClaim/PepperUnion 线程纪律守卫）：
 * Async 阶段断言、带上下文重载、主线程标记 IO 断言、未开启不误伤。
 */
class ThreadGuardTest {

    @AfterEach
    void tearDown() {
        ThreadGuard.exitAsync();
        ThreadGuard.exitMainThread();
        ThreadGuard.setEnforcementEnabled(false);
    }

    @Test
    void asyncAssertionFiresWhenEnforcedInsideAsync() {
        ThreadGuard.setEnforcementEnabled(true);
        ThreadGuard.enterAsync();
        assertThrows(IllegalStateException.class, ThreadGuard::assertNotAsync);
    }

    @Test
    void asyncAssertionWithContextCarriesMessage() {
        ThreadGuard.setEnforcementEnabled(true);
        ThreadGuard.enterAsync();
        final IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> ThreadGuard.assertNotAsync("write forbidden"));
        assertTrue(e.getMessage().contains("write forbidden"));
    }

    @Test
    void asyncAssertionPassesOutsideAsyncOrWhenNotEnforced() {
        ThreadGuard.setEnforcementEnabled(true);
        assertDoesNotThrow(() -> ThreadGuard.assertNotAsync());

        ThreadGuard.setEnforcementEnabled(false);
        ThreadGuard.enterAsync();
        assertDoesNotThrow(() -> ThreadGuard.assertNotAsync());
    }

    @Test
    void mainThreadIoAssertionFiresWhenEnforcedOnMainThread() {
        ThreadGuard.setEnforcementEnabled(true);
        ThreadGuard.enterMainThread();
        assertThrows(IllegalStateException.class, ThreadGuard::assertMainThreadIo);
    }

    @Test
    void mainThreadIoAssertionPassesOffMainThreadOrWhenNotEnforced() {
        ThreadGuard.setEnforcementEnabled(true);
        assertDoesNotThrow(() -> ThreadGuard.assertMainThreadIo());

        ThreadGuard.setEnforcementEnabled(false);
        ThreadGuard.enterMainThread();
        assertDoesNotThrow(() -> ThreadGuard.assertMainThreadIo());
    }

    @Test
    void stateFlagsReflectCurrentPhase() {
        assertFalse(ThreadGuard.isInAsync());
        assertFalse(ThreadGuard.isMainThread());

        ThreadGuard.enterAsync();
        assertTrue(ThreadGuard.isInAsync());
        ThreadGuard.enterMainThread();
        assertTrue(ThreadGuard.isMainThread());
    }
}
