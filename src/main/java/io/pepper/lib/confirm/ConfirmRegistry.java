package io.pepper.lib.confirm;

import io.pepper.lib.validation.Preconditions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用的命令二次确认注册表：{@link #register} 记录每个玩家待确认的操作，
 * 之后由插件的确认命令（如 {@code /union confirm}、{@code /claim confirm}）
 * 取出并执行。
 *
 * <p>每个玩家同时只有一条待确认操作，新的注册会覆盖旧的。当玩家退出 / 被踢出 /
 * 插件重载时，通过 {@link #clear} / {@link #clearAll} 清除记录，避免旧确认
 * 被错误地应用到之后的新状态上。统一两插件实现（PepperUnion
 * {@code ConfirmRegistry} 与 PepperClaim {@code ConfirmRegistry}）。</p>
 *
 * @param <T> 待确认操作类型
 */
public final class ConfirmRegistry<T> {

    private final Map<UUID, ConfirmEntry<T>> pending = new ConcurrentHashMap<>();

    /**
     * 为玩家登记一条待确认操作（覆盖任何之前的记录）。
     *
     * @param playerUuid 玩家 UUID
     * @param action 待确认操作
     * @param ttlMillis 有效时长（毫秒），必须为正
     * @throws IllegalArgumentException ttl 非正
     */
    public void register(final UUID playerUuid, final T action, final long ttlMillis) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        Preconditions.requireNonNull(action, "action");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0, got " + ttlMillis);
        }
        this.pending.put(playerUuid, new ConfirmEntry<>(action, System.currentTimeMillis() + ttlMillis));
    }

    /**
     * 登记一条待确认操作；当 {@code ttlMillis <= 0}（例如配置为 0 表示免确认）时
     * 跳过二次确认、立即执行 {@code immediateRun}，并返回 {@code true}；否则登记并返回
     * {@code false}。
     *
     * <p>与 {@link #register} 不同，这里把 {@code 0} 解释为「免确认、直接执行」，
     * 避免登记一条立即失效的操作，导致确认命令永远返回过期而无法完成。</p>
     *
     * @param playerUuid 玩家 UUID
     * @param action 待确认操作
     * @param ttlMillis 有效时长（毫秒）；非正表示免确认
     * @param immediateRun 免确认时的立即执行动作（非空）
     * @return {@code true} 表示已立即执行（未登记）；{@code false} 表示已登记待确认
     */
    public boolean registerOrRun(
            final UUID playerUuid, final T action, final long ttlMillis, final Runnable immediateRun) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        Preconditions.requireNonNull(action, "action");
        Preconditions.requireNonNull(immediateRun, "immediateRun");
        if (ttlMillis <= 0) {
            // 免确认直接执行，但先清掉该玩家可能残留的旧待确认操作，
            // 避免确认命令之后执行到一条已被本操作取代的过期动作。
            this.pending.remove(playerUuid);
            immediateRun.run();
            return true;
        }
        this.pending.put(playerUuid, new ConfirmEntry<>(action, System.currentTimeMillis() + ttlMillis));
        return false;
    }

    /**
     * 取出并移除该玩家的待确认操作。
     *
     * @param playerUuid 玩家 UUID
     * @return 未过期条目；缺失或已过期返回 {@code empty}（过期条目同时被清理）
     */
    public Optional<ConfirmEntry<T>> consume(final UUID playerUuid) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        final ConfirmEntry<T> entry = this.pending.remove(playerUuid);
        if (entry == null || entry.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /** 清除该玩家的待确认记录（退出 / 被踢出时调用）。 */
    public void clear(final UUID playerUuid) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        this.pending.remove(playerUuid);
    }

    /** 清空全部待确认记录（插件重载时调用：旧上下文的待确认操作全部失效）。 */
    public void clearAll() {
        this.pending.clear();
    }

    /** 惰性清扫所有过期条目。 */
    public int clearExpired() {
        final long now = System.currentTimeMillis();
        int removed = 0;
        for (final var iterator = this.pending.entrySet().iterator(); iterator.hasNext(); ) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 当前待确认条目数。 */
    public int size() {
        return this.pending.size();
    }
}
