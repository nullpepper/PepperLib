package ltd.pepper.lib.storage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 两级精准缓存失效总线。
 *
 * <p>消除基于全表时间戳轮询的惊群风暴（Thundering Herd）。
 * 支持单服模式（本地 EventBus 内存分发，零外部中间件依赖）与
 * 跨服模式（对接 Redis Pub/Sub 或 PluginMessage）。</p>
 */
public final class CacheInvalidationBus {

    public record InvalidationMessage(String entityType, String entityId, long timestamp) {
        public static InvalidationMessage of(String entityType, String entityId) {
            return new InvalidationMessage(entityType, entityId, System.currentTimeMillis());
        }
    }

    private final Consumer<InvalidationMessage> externalPublisher;
    private final List<Consumer<InvalidationMessage>> localListeners = new CopyOnWriteArrayList<>();

    /**
     * 单服模式构造：纯本地分发，不依赖外部网络与 Redis。
     */
    public static CacheInvalidationBus local() {
        return new CacheInvalidationBus(null);
    }

    /**
     * 跨服模式构造：本地广播同时通过外部发布者发送。
     */
    public static CacheInvalidationBus distributed(final Consumer<InvalidationMessage> externalPublisher) {
        return new CacheInvalidationBus(Objects.requireNonNull(externalPublisher, "externalPublisher"));
    }

    public CacheInvalidationBus(final Consumer<InvalidationMessage> externalPublisher) {
        this.externalPublisher = externalPublisher;
    }

    /**
     * 注册本地缓存失效监听器。
     */
    public void subscribe(final Consumer<InvalidationMessage> listener) {
        if (listener != null) {
            this.localListeners.add(listener);
        }
    }

    /**
     * 收到外部消息（如 Redis 订阅者推送）时通知本地所有监听器。
     */
    public void onExternalMessage(final InvalidationMessage message) {
        if (message != null) {
            for (final Consumer<InvalidationMessage> listener : this.localListeners) {
                try {
                    listener.accept(message);
                } catch (final Throwable ignored) {
                }
            }
        }
    }

    /**
     * 精准失效单个实体缓存。
     */
    public void invalidateEntity(final String entityType, final String entityId) {
        final InvalidationMessage msg = InvalidationMessage.of(entityType, entityId);
        onExternalMessage(msg);
        if (this.externalPublisher != null) {
            try {
                this.externalPublisher.accept(msg);
            } catch (final Throwable ignored) {
            }
        }
    }

    /**
     * 精准失效单个玩家的缓存。
     */
    public void invalidatePlayer(final UUID playerUuid) {
        if (playerUuid != null) {
            invalidateEntity("PLAYER", playerUuid.toString());
        }
    }
}
