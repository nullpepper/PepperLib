package ltd.pepper.lib.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用 per-key 冷却槽（源自 PepperBotCustomMessage {@code CooldownTracker} 提取，已解耦为 key-based）。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>{@link #tryAcquire} 原子"检查并抢占"冷却槽——并发同 key 只放行一次；</li>
 *   <li>{@link #release} 释放之前抢占的槽（remove-if-equals，避免误删并发下更新的记录）；</li>
 *   <li>{@link #shouldSendTip} 独立提示节流（每个冷却窗口最多提示一次）；</li>
 *   <li>{@link #isActive} / {@link #remainingMillis} 查询；{@link #clear} 整体重置。</li>
 * </ul>
 *
 * <p>窗口与作用域折叠由调用方负责：每次调用显式传入 {@code windowMillis}（毫秒），
 * key 由调用方拼接（如 {@code "ruleId|user|userId"}）。{@code windowMillis <= 0} 视为禁用：
 * {@link #tryAcquire} 恒放行、{@link #isActive} 恒 false、{@link #shouldSendTip} 恒 false、
 * {@link #recordFire} 为 no-op、{@link #remainingMillis} 恒 0（与原版 disabled 语义一致）。</p>
 *
 * <p>线程安全：所有状态存于 {@link ConcurrentHashMap}，抢占/节流判定在 {@code compute} 内原子完成。</p>
 */
public final class CooldownTracker {

    private final ConcurrentHashMap<String, Long> lastFire = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTip = new ConcurrentHashMap<>();

    /** 窗口内是否处于冷却（windowMillis &lt;= 0 恒 false）。 */
    public boolean isActive(String key, long windowMillis, long now) {
        if (windowMillis <= 0) {
            return false;
        }
        Long last = lastFire.get(key);
        return last != null && now - last < windowMillis;
    }

    /**
     * 原子"检查并抢占"冷却槽：把分离的 {@code isActive} + 记录合并为一次 {@code compute}，
     * 保证并发调用只放行一个、其余进入冷却路径。
     *
     * @return true = 成功抢占（此前未冷却），调用方应继续；false = 冷却中
     */
    public boolean tryAcquire(String key, long windowMillis, long now) {
        if (windowMillis <= 0) {
            return true;
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        lastFire.compute(key, (k, last) -> {
            if (last != null && now - last < windowMillis) {
                return last; // 冷却中：保持原值，不放行
            }
            acquired.set(true);
            return now; // 抢占：记录本次触发时间
        });
        return acquired.get();
    }

    /**
     * 释放之前由 {@link #tryAcquire} 占用的冷却槽（仅当本次执行失败或未产出任何结果时调用）。
     * 使用 remove-if-equals，避免误删并发下更新的、更新的记录。
     *
     * @param reservedAt {@link #tryAcquire} 成功时调用方持有的 now 值
     */
    public void release(String key, long reservedAt) {
        lastFire.remove(key, reservedAt);
    }

    /** 提示节流：每个冷却窗口最多发送一次提示（独立于冷却槽的 map）。 */
    public boolean shouldSendTip(String key, long windowMillis, long now) {
        if (windowMillis <= 0) {
            return false;
        }
        AtomicBoolean send = new AtomicBoolean(false);
        lastTip.compute(key, (k, last) -> {
            if (last == null || now - last >= windowMillis) {
                send.set(true);
                return now; // 原子地记录本次提示，防并发重复发送
            }
            return last;
        });
        return send.get();
    }

    /** 记录一次触发（供无抢占需求的调用方使用）；windowMillis &lt;= 0 时为 no-op。 */
    public void recordFire(String key, long windowMillis, long now) {
        if (windowMillis <= 0) {
            return;
        }
        lastFire.put(key, now);
    }

    /** 剩余冷却毫秒数（未冷却/无记录/禁用返回 0）。 */
    public long remainingMillis(String key, long windowMillis, long now) {
        if (windowMillis <= 0) {
            return 0;
        }
        Long last = lastFire.get(key);
        if (last == null) {
            return 0;
        }
        return Math.max(0, last + windowMillis - now);
    }

    /** 清空全部冷却与提示状态（如重载配置时）。 */
    public void clear() {
        lastFire.clear();
        lastTip.clear();
    }
}
