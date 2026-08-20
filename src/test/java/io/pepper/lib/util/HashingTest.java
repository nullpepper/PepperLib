package io.pepper.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SHA-256 hex 哈希工具（源自 PepperBotCustomMessage 提取）。 */
class HashingTest {

    @Test
    @DisplayName("已知向量：abc 与空串的 SHA-256")
    void knownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256(""));
    }

    @Test
    @DisplayName("输出为 64 位小写 hex")
    void outputIsLowercaseHex() {
        String hash = Hashing.sha256("PepperBotCustomMessage");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写 hex: " + hash);
    }

    @Test
    @DisplayName("相同输入同输出、不同输入不同输出")
    void deterministicAndDistinct() {
        assertEquals(Hashing.sha256("same"), Hashing.sha256("same"));
        assertNotEquals(Hashing.sha256("a"), Hashing.sha256("b"));
    }

    @Test
    @DisplayName("UTF-8 编码：中文输入不抛异常且长度恒定")
    void utf8Input() {
        String hash = Hashing.sha256("你好世界");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
