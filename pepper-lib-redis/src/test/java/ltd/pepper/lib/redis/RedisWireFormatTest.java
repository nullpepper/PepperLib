package ltd.pepper.lib.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 线上格式的字节级校验：RESP 头里的长度必须是<b>字节数</b>。 */
class RedisWireFormatTest {

    @Test
    @DisplayName("纯 ASCII 命令的字节布局")
    void asciiCommand() {
        byte[] bytes = RedisClient.buildCommand("SET", "k", "v");
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("含中文的命令：长度头是字节数，不是字符数")
    void multibyteCommand() {
        String value = "胡椒工艺";
        assertEquals(12, value.getBytes(StandardCharsets.UTF_8).length, "4 个汉字 = 12 字节");
        assertEquals(4, value.length(), "4 个字符");

        byte[] bytes = RedisClient.buildCommand("SET", "k", value);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(
                text.startsWith("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$12\r\n"),
                () -> "长度头应为 12（字节数），实际前缀：" + text.substring(0, Math.min(30, text.length())));
        assertTrue(text.endsWith(value + "\r\n"), "载荷应原样跟在后面");
    }

    @Test
    @DisplayName("多行载荷（含 \\n）的字节数正确")
    void multilinePayload() {
        String payload = "pepperenv/v1\nredis=redis:6379:chan\nenv:\nCJK=胡椒工艺\n";
        int expected = payload.getBytes(StandardCharsets.UTF_8).length;
        byte[] bytes = RedisClient.buildCommand("SET", "key", payload);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(
                text.startsWith("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$" + expected + "\r\n"),
                () -> "长度头应为 " + expected + "，实际：" + text.substring(0, Math.min(40, text.length())));
    }
}
