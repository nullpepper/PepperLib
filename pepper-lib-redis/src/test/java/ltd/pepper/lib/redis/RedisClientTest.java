package ltd.pepper.lib.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ltd.pepper.lib.redis.RedisClient.RedisException;
import ltd.pepper.lib.redis.RedisClient.RedisSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 最小 RESP2 客户端的协议正确性。
 *
 * <p>用 {@link FakeRedisServer} 在本地端口上真实收发字节 —— 校验的是<b>线上格式</b>，
 * 而不是「我以为我发的格式」。真实 Redis 的端到端验证见 RedisLiveTest。</p>
 */
class RedisClientTest {

    private FakeRedisServer fake;

    private RedisClient clientFor(FakeRedisServer fake) {
        return new RedisClient(new RedisSettings("127.0.0.1", fake.port(), "test:env"));
    }

    @AfterEach
    void tearDown() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    @DisplayName("PING 成功即连接可用")
    void ping() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            assertTrue(client.ping(), "PING 应返回 true");
            assertTrue(client.isConnected());
        }
    }

    @Test
    @DisplayName("SET 后 GET 能取回同样的值（含中文与特殊字符）")
    void setThenGet() throws Exception {
        fake = new FakeRedisServer();
        String payload = "pepperenv/v1\nredis=redis:6379:chan\nenv:\nCJK=胡椒工艺\nURL=http://a:1/b=c\n";
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            assertTrue(client.set("pepperenv:env:payload", payload));

            assertEquals(payload, client.get("pepperenv:env:payload"), "取回的值必须逐字节一致");
            assertEquals(payload, fake.stored("pepperenv:env:payload"), "服务端应真的存下了");
        }
    }

    @Test
    @DisplayName("GET 不存在的键返回 null（不是空串）")
    void getMissing() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            assertNull(client.get("nope"));
        }
    }

    @Test
    @DisplayName("PUBLISH 返回收到消息的订阅者数量")
    void publish() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            assertEquals(0, client.publish("chan", "payload"), "没有订阅者时应为 0");

            try (RedisSubscription sub = client.subscribe("chan", msg -> {})) {
                awaitSubscriber(fake);
                assertEquals(1, client.publish("chan", "payload"), "应报告 1 个订阅者");
            }
        }
    }

    @Test
    @DisplayName("订阅能收到推送消息（内容正确、顺序正确）")
    void subscriptionReceivesMessages() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);

            try (RedisSubscription sub = client.subscribe("test:env", msg -> {
                received.add(msg);
                latch.countDown();
            })) {
                awaitSubscriber(fake);
                fake.push("test:env", "第一条");
                fake.push("test:env", "第二条");

                assertTrue(latch.await(3, TimeUnit.SECONDS), () -> "应收到两条推送，实际：" + received);
                assertEquals(List.of("第一条", "第二条"), received);
            }
        }
    }

    @Test
    @DisplayName("订阅能收到含特殊字符的载荷（验证不是按字符数读）")
    void subscriptionHandlesMultibytePayload() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            String payload = "pepperenv/v1\nredis=redis:6379:chan\nenv:\nCJK=胡椒工艺\n";

            try (RedisSubscription sub = client.subscribe("test:env", msg -> {
                received.add(msg);
                latch.countDown();
            })) {
                awaitSubscriber(fake);
                fake.push("test:env", payload);

                assertTrue(latch.await(3, TimeUnit.SECONDS), () -> "应收到推送，实际：" + received);
                assertEquals(payload, received.get(0), "多字节载荷必须逐字节一致");
            }
        }
    }

    @Test
    @DisplayName("订阅期间断线：自动重连并通知调用方重新拉全量")
    void subscriptionReconnects() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch reconnected = new CountDownLatch(1);
            List<String> received = new CopyOnWriteArrayList<>();

            try (RedisSubscription sub = client.subscribe(
                    "test:env",
                    msg -> {
                        received.add(msg);
                        first.countDown();
                    },
                    reconnected::countDown)) {
                awaitSubscriber(fake);
                fake.push("test:env", "before");
                assertTrue(first.await(3, TimeUnit.SECONDS), "断线前应收到第一条");

                fake.dropSubscribers(); // 模拟服务端断开订阅连接

                assertTrue(reconnected.await(6, TimeUnit.SECONDS), "订阅断线后应自动重连并回调 onReconnected");
                assertTrue(sub.isActive(), "订阅线程应仍在运行");
            }
        }
    }

    @Test
    @DisplayName("Redis 返回错误：get/set 明确失败，不返回垃圾值")
    void redisErrorSurfaces() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            fake.forceError = "NOAUTH Authentication required";

            assertThrows(RedisException.class, () -> client.get("k"), "GET 收到 -ERR 应抛 RedisException");
            assertThrows(RedisException.class, () -> client.set("k", "v"), "SET 收到 -ERR 应抛 RedisException");
        }
    }

    @Test
    @DisplayName("协议被破坏（垃圾响应）：明确失败而不是解析出垃圾值")
    void garbageResponseSurfaces() throws Exception {
        fake = new FakeRedisServer();
        try (RedisClient client = clientFor(fake)) {
            client.connect();
            fake.forceGarbage = "这不是 RESP 响应\r\n";

            assertThrows(RedisException.class, () -> client.get("k"));
        }
    }

    @Test
    @DisplayName("连不上时 connect() 抛出带原因的异常（调用方据此报错）")
    void connectFailure() {
        RedisSettings settings = new RedisSettings("127.0.0.1", 1, "test:env");
        try (RedisClient client = new RedisClient(settings)) {
            RedisException ex = assertThrows(RedisException.class, client::connect);
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("127.0.0.1"), () -> "错误信息应含目标地址：" + ex.getMessage());
            assertFalse(client.isConnected());
        }
    }

    @Test
    @DisplayName("未连接就发命令：抛异常而不是静默失败")
    void commandsRequireConnection() {
        RedisSettings settings = new RedisSettings("127.0.0.1", 1, "test:env");
        try (RedisClient client = new RedisClient(settings)) {
            assertThrows(RedisException.class, () -> client.get("k"));
        }
    }

    @Test
    @DisplayName("close() 后订阅线程退出，不泄漏线程")
    void closeStopsSubscription() throws Exception {
        fake = new FakeRedisServer();
        RedisClient client = clientFor(fake);
        client.connect();
        RedisSubscription sub = client.subscribe("test:env", msg -> {});
        awaitSubscriber(fake);
        assertTrue(sub.isActive());

        sub.close();
        client.close();

        for (int i = 0; i < 40 && sub.isActive(); i++) {
            Thread.sleep(50);
        }
        assertFalse(sub.isActive(), "close() 后订阅线程应退出");
    }

    /** 等假服务端登记到订阅者（消除竞态：先就绪再推消息）。 */
    private static void awaitSubscriber(FakeRedisServer fake) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (fake.subscriberCount() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("订阅者未在预期时间内登记");
    }
}
