package ltd.pepper.lib.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import ltd.pepper.lib.redis.RedisClient.RedisSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅在<b>空闲</b>时的稳定性。
 *
 * <p>真实缺陷回归测试：读超时（通道安静一段时间）曾被当成断线处理 ——
 * 因为 {@code catch} 里的 {@code continue} 跳到了<b>外层</b>循环，
 * 于是每次超时都拆掉连接重连。表现是生产日志里每 3 秒一条「订阅已重连」，
 * Redis 侧能看到每 3 秒一个新连接。</p>
 *
 * <p>正确行为：读超时只是「这段时间没有消息」，连接必须保持。</p>
 */
class RedisSubscriptionIdleTest {

    private FakeRedisServer fake;
    private RedisClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    @DisplayName("空闲不重连：等待超过多个读超时窗口，连接数不应增加")
    void staysConnectedWhileIdle() throws Exception {
        fake = new FakeRedisServer();
        client = new RedisClient(new RedisSettings("127.0.0.1", fake.port(), "test:env"));
        client.connect();

        AtomicInteger reconnects = new AtomicInteger();
        try (RedisSubscription sub = client.subscribe("test:env", msg -> {}, reconnects::incrementAndGet)) {
            await("订阅登记", () -> fake.subscriberCount() > 0);
            int connectsAfterSubscribe = fake.connectCount();

            // 静默等待：读超时是 3 秒，这里跨过 2 个窗口
            Thread.sleep(7_000);

            assertEquals(0, reconnects.get(), "空闲期间不应触发重连");
            assertEquals(connectsAfterSubscribe, fake.connectCount(), "空闲期间不应新建连接（每 3 秒一个新连接就是这个 bug 的症状）");
            assertTrue(sub.isActive(), "订阅线程应仍在运行");
        }
    }

    @Test
    @DisplayName("空闲后仍能收到推送（连接确实是活的，不是假装活着）")
    void stillReceivesAfterIdle() throws Exception {
        fake = new FakeRedisServer();
        client = new RedisClient(new RedisSettings("127.0.0.1", fake.port(), "test:env"));
        client.connect();

        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try (RedisSubscription sub = client.subscribe(
                "test:env",
                msg -> {
                    received.add(msg);
                    latch.countDown();
                },
                null)) {
            await("订阅登记", () -> fake.subscriberCount() > 0);

            Thread.sleep(5_000); // 跨过一个读超时窗口
            fake.push("test:env", "空闲之后的消息");

            assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS), () -> "空闲后应仍能收到推送，实际：" + received);
            assertEquals("空闲之后的消息", received.get(0));
        }
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("等待超时：" + what);
    }
}
