package ltd.pepper.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通用 per-key 冷却槽（源自 PepperBotCustomMessage CooldownTracker 提取，已解耦为 key-based）。
 *
 * <p>语义与原版逐条对应：窗口边界（now - last &lt; window 激活）、原子抢占、remove-if-equals
 * 释放、tip 节流独立 map、windowMillis &lt;= 0 视为禁用（直通）。scope/规则折叠由调用方
 * 构造 key 完成（如 {@code "ruleId|user|userId"}）。
 */
class CooldownTrackerTest {

    private static final String KEY = "r1|user|u1";
    private static final long WINDOW = 5000;

    @Test
    @DisplayName("窗口内激活、到期失效（边界 4999/5000）")
    void activeUntilWindowExpires() {
        CooldownTracker tracker = new CooldownTracker();
        long t = 1000;
        assertFalse(tracker.isActive(KEY, WINDOW, t));

        tracker.recordFire(KEY, WINDOW, t);
        assertTrue(tracker.isActive(KEY, WINDOW, t));
        assertTrue(tracker.isActive(KEY, WINDOW, t + 4000));
        assertTrue(tracker.isActive(KEY, WINDOW, t + 4999));
        assertFalse(tracker.isActive(KEY, WINDOW, t + 5000));
    }

    @Test
    @DisplayName("不同 key 互不影响（隔离由调用方 key 保证）")
    void keysAreIsolated() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.recordFire("r1|user|u1", WINDOW, 0);
        assertTrue(tracker.isActive("r1|user|u1", WINDOW, 1000));
        assertFalse(tracker.isActive("r1|user|u2", WINDOW, 1000));
        assertFalse(tracker.isActive("r2|user|u1", WINDOW, 1000));
    }

    @Test
    @DisplayName("remainingMillis 剩余时间与到期归零")
    void remainingMillis() {
        CooldownTracker tracker = new CooldownTracker();
        assertEquals(0, tracker.remainingMillis(KEY, WINDOW, 0));

        tracker.recordFire(KEY, WINDOW, 1000);
        assertEquals(3000, tracker.remainingMillis(KEY, WINDOW, 3000));
        assertEquals(0, tracker.remainingMillis(KEY, WINDOW, 6000));
        assertEquals(0, tracker.remainingMillis(KEY, WINDOW, 8000));
    }

    @Test
    @DisplayName("提示每个冷却窗口最多发送一次")
    void tipSentAtMostOncePerWindow() {
        CooldownTracker tracker = new CooldownTracker();
        long t = 0;
        assertTrue(tracker.shouldSendTip(KEY, WINDOW, t));
        assertFalse(tracker.shouldSendTip(KEY, WINDOW, t + 1000));
        assertFalse(tracker.shouldSendTip(KEY, WINDOW, t + 4000));
        assertTrue(tracker.shouldSendTip(KEY, WINDOW, t + 5000));
    }

    @Test
    @DisplayName("禁用（window <= 0）时全部直通：不激活、不提示、recordFire no-op")
    void disabledWindowPassesThrough() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.recordFire(KEY, WINDOW, 0);
        assertFalse(tracker.isActive(KEY, 0, 1));
        assertTrue(tracker.tryAcquire(KEY, 0, 1));
        assertFalse(tracker.shouldSendTip(KEY, 0, 1));
        assertEquals(0, tracker.remainingMillis(KEY, 0, 1));
    }

    @Test
    @DisplayName("clear 重置所有冷却与提示状态")
    void clearResetsEverything() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.recordFire(KEY, WINDOW, 0);
        tracker.shouldSendTip(KEY, WINDOW, 0);
        assertTrue(tracker.isActive(KEY, WINDOW, 1000));

        tracker.clear();
        assertFalse(tracker.isActive(KEY, WINDOW, 1000));
        assertTrue(tracker.shouldSendTip(KEY, WINDOW, 1000));
    }

    @Test
    @DisplayName("tryAcquire 原子抢占：同 key 连续获取第二次失败，窗口过后恢复")
    void tryAcquireIsAtomic() {
        CooldownTracker tracker = new CooldownTracker();
        long t = 1000;
        assertTrue(tracker.tryAcquire(KEY, WINDOW, t));
        assertFalse(tracker.tryAcquire(KEY, WINDOW, t + 1));
        assertFalse(tracker.tryAcquire(KEY, WINDOW, t + 4999));
        assertTrue(tracker.tryAcquire(KEY, WINDOW, t + 5000));
    }

    @Test
    @DisplayName("release 用 remove-if-equals：正确 reservedAt 释放，错误值不释放")
    void releaseUsesRemoveIfEquals() {
        CooldownTracker tracker = new CooldownTracker();
        long t = 1000;
        assertTrue(tracker.tryAcquire(KEY, WINDOW, t));

        // 错误值（如并发下另一线程更新的时间）不释放
        tracker.release(KEY, t - 1);
        assertTrue(tracker.isActive(KEY, WINDOW, t + 1));

        // 正确 reservedAt 释放
        tracker.release(KEY, t);
        assertFalse(tracker.isActive(KEY, WINDOW, t + 1));
        assertTrue(tracker.tryAcquire(KEY, WINDOW, t + 1));
    }

    @Test
    @DisplayName("tryAcquire 抢占后 remainingMillis 反映窗口")
    void remainingAfterTryAcquire() {
        CooldownTracker tracker = new CooldownTracker();
        long t = 1000;
        assertTrue(tracker.tryAcquire(KEY, WINDOW, t));
        assertEquals(4000, tracker.remainingMillis(KEY, WINDOW, t + 1000));
        assertEquals(0, tracker.remainingMillis(KEY, WINDOW, t + WINDOW));
    }
}
