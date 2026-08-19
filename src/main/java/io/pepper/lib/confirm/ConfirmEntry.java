package io.pepper.lib.confirm;

import io.pepper.lib.validation.Preconditions;

/**
 * 一条待确认操作：TTL 内可被 consume，过期自动失效。
 *
 * <p>统一两插件的待确认形态：PepperUnion {@code PendingAction} 与
 * PepperClaim {@code ConfirmIntent}（PepperLib-Skeleton-Draft §2.1）。</p>
 *
 * @param action 待确认操作（插件业务类型，如意图/动作对象）
 * @param expiresAt 过期时间戳（epoch 毫秒）
 */
public record ConfirmEntry<T>(T action, long expiresAt) {

    public ConfirmEntry {
        Preconditions.requireNonNull(action, "action");
    }

    /** 是否已过期。 */
    public boolean isExpired() {
        return System.currentTimeMillis() >= this.expiresAt;
    }
}
