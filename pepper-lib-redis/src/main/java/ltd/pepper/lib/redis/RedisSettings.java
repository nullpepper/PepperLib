package ltd.pepper.lib.redis;

/**
 * Redis 连接设置：变量同步的传输端点。
 *
 * <p><b>为什么这些值会跟着载荷一起走</b>：后端不需要自己维护一份 Redis 配置 ——
 * 它先按默认位置（{@link #defaults()}）读一份载荷，就知道该连哪里、订阅哪个通道。
 * 管理员只改代理端的 config.yml 一处。</p>
 *
 * @param host    Redis 主机名（容器网络里通常是 {@code redis}）
 * @param port    Redis 端口
 * @param channel 同步通道名；{@link #key()} 由它派生
 */
public record RedisSettings(String host, int port, String channel) {

    /** 默认通道名。 */
    public static final String DEFAULT_CHANNEL = "pepperenv:env";

    public RedisSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("redis host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("redis port out of range: " + port);
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("redis channel must not be blank");
        }
    }

    /** 默认设置：容器网络里的 {@code redis:6379}。 */
    public static RedisSettings defaults() {
        return new RedisSettings("redis", 6379, DEFAULT_CHANNEL);
    }

    /**
     * 承载最新载荷的 key。
     *
     * <p>刻意与通道名不同：Redis 里 key 空间与 pub/sub 通道是两套命名空间，
     * 同名会让人误以为 {@code SUBSCRIBE} 能读到 {@code GET} 的内容。
     * 后端启动时 {@code GET} 它做全量引导，之后靠订阅增量更新。</p>
     */
    public String key() {
        return channel + ":payload";
    }

    @Override
    public String toString() {
        return host + ":" + port + " channel=" + channel;
    }
}
