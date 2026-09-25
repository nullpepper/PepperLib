package ltd.pepper.lib.confirm;

import ltd.pepper.lib.validation.Preconditions;

/**
 * 一条待确认操作：TTL 内可被 consume，过期自动失效。
 *
 * <p>统一两插件的待确认形态：PepperUnion {@code PendingAction} 与
 * PepperClaim {@code ConfirmIntent}（内部设计文档 skeleton-draft §2.1）。</p>
 *
 * @param action 待确认操作（插件业务类型，如意图/动作对象）
 * @param createdAt 注册时间戳（epoch 毫秒），消费方可据此拒绝同 tick 双提交
 * @param expiresAt 过期时间戳（epoch 毫秒）
 */
public record ConfirmEntry<T>(T action, long createdAt, long expiresAt) {

    public ConfirmEntry {
        Preconditions.requireNonNull(action, "action");
        if (createdAt > expiresAt) {
            throw new IllegalArgumentException("createdAt must not be after expiresAt");
        }
    }

    /** 是否已过期。 */
    public boolean isExpired() {
        return System.currentTimeMillis() >= this.expiresAt;
    }

    /** 注册至今的存续时长（毫秒）。 */
    public long ageMillis() {
        return System.currentTimeMillis() - this.createdAt;
    }
}
