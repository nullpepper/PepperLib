package ltd.pepper.lib.confirm;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import ltd.pepper.lib.validation.Preconditions;

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
     * <p>登记前惰性清扫全部过期条目（{@link #clearExpired()}）：长在线玩家
     * 的过期确认不会滞留内存；复杂度 O(在线玩家数)，登记路径可接受。</p>
     *
     * <p>覆盖提示（PepperUnion #19）：被覆盖的旧条目若**未过期**，随返回值交还调用方，
     * 由调用方决定是否提示玩家「上一条待确认操作已被覆盖」；已过期条目不算覆盖，
     * 返回 {@code empty}。</p>
     *
     * @param playerUuid 玩家 UUID
     * @param action 待确认操作
     * @param ttlMillis 有效时长（毫秒），必须为正
     * @return 被本次登记覆盖的未过期旧条目；无覆盖返回 {@code empty}
     * @throws IllegalArgumentException ttl 非正
     */
    public Optional<ConfirmEntry<T>> register(final UUID playerUuid, final T action, final long ttlMillis) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        Preconditions.requireNonNull(action, "action");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0, got " + ttlMillis);
        }
        this.clearExpired();
        return this.put(playerUuid, action, ttlMillis);
    }

    /** 登记并返回被覆盖的未过期旧条目（{@link #register} 的内部共用实现）。 */
    private Optional<ConfirmEntry<T>> put(final UUID playerUuid, final T action, final long ttlMillis) {
        final long now = System.currentTimeMillis();
        return Optional.ofNullable(this.pending.put(playerUuid, new ConfirmEntry<>(action, now, now + ttlMillis)))
                .filter(displaced -> !displaced.isExpired());
    }

    /**
     * 非消费查看该玩家的待确认操作（同 tick 双提交防护用：先看条目年龄再决定是否 consume）。
     *
     * @param playerUuid 玩家 UUID
     * @return 未过期条目；缺失或已过期返回 {@code empty}（不移除）
     */
    public Optional<ConfirmEntry<T>> peek(final UUID playerUuid) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        final ConfirmEntry<T> entry = this.pending.get(playerUuid);
        return entry == null || entry.isExpired() ? Optional.empty() : Optional.of(entry);
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
        return this.registerOrRun(playerUuid, action, ttlMillis, immediateRun, displaced -> {});
    }

    /**
     * {@link #registerOrRun(UUID, Object, long, Runnable)} 的覆盖提示版：
     * 覆盖到未过期旧条目时以该条目回调 {@code onOverwrite}（供调用方提示玩家）。
     *
     * @param onOverwrite 覆盖未过期旧条目时的回调（非空；无覆盖不触发）
     * @return {@code true} 表示已立即执行（未登记）；{@code false} 表示已登记待确认
     */
    public boolean registerOrRun(
            final UUID playerUuid,
            final T action,
            final long ttlMillis,
            final Runnable immediateRun,
            final Consumer<ConfirmEntry<T>> onOverwrite) {
        Preconditions.requireNonNull(playerUuid, "playerUuid");
        Preconditions.requireNonNull(action, "action");
        Preconditions.requireNonNull(immediateRun, "immediateRun");
        Preconditions.requireNonNull(onOverwrite, "onOverwrite");
        if (ttlMillis <= 0) {
            // 免确认直接执行，但先清掉该玩家可能残留的旧待确认操作，
            // 避免确认命令之后执行到一条已被本操作取代的过期动作。
            this.pending.remove(playerUuid);
            immediateRun.run();
            return true;
        }
        this.clearExpired();
        this.put(playerUuid, action, ttlMillis).ifPresent(onOverwrite);
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
