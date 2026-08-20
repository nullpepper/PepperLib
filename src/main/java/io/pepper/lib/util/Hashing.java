package io.pepper.lib.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 哈希工具（源自 PepperBotCustomMessage 提取）：SHA-256 + 查表 hex。 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {}

    /** UTF-8 编码输入，返回 64 位小写 hex SHA-256。 */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK17 上不可能发生；显式失败而非静默降级
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
