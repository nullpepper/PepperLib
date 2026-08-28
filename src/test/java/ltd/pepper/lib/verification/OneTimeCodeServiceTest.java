package ltd.pepper.lib.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import ltd.pepper.lib.verification.OneTimeCodeService.Issued;
import ltd.pepper.lib.verification.OneTimeCodeService.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 一次性验证码服务核心语义（源自 PepperBotBindManager VerificationManager 提取）。 */
class OneTimeCodeServiceTest {

    private static final Settings DEFAULT_SETTINGS = new Settings(6, false, 300, 60);

    @Test
    @DisplayName("issue 返回唯一码，consume 一次性原子消费")
    void issueUniqueCodesAndConsumeOnce() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        Issued<String> first = service.issue("payload-a", Duration.ofSeconds(300));
        Issued<String> second = service.issue("payload-b", Duration.ofSeconds(300));
        assertNotNull(first.code());
        assertNotNull(second.code());
        assertNotEquals(first.code(), second.code(), "验证码必须唯一");

        Optional<Issued<String>> consumed = service.consume(first.code());
        assertTrue(consumed.isPresent());
        assertEquals("payload-a", consumed.get().payload());
        assertEquals(first.code(), consumed.get().code());
        assertEquals(first.expiresAt(), consumed.get().expiresAt());
        assertTrue(service.consume(first.code()).isEmpty(), "验证码只能消费一次");
        assertTrue(service.consume("no-such-code").isEmpty(), "未知验证码返回空");
    }

    @Test
    @DisplayName("过期验证码不可消费（ttl=0 立即过期）")
    void expiredCodeRejected() throws Exception {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(6, false, 0, 0));
        String code = service.issue("payload", Duration.ofSeconds(0)).code();
        // 等待越过同一时钟刻度，避免 now == expiresAt 的竞态
        Thread.sleep(10);
        assertTrue(service.consume(code).isEmpty(), "过期验证码不应消费成功");
    }

    @Test
    @DisplayName("peek 非破坏性查看，不消耗验证码")
    void peekIsNonDestructive() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        Issued<String> issued = service.issue("payload", Duration.ofSeconds(300));
        Optional<Issued<String>> peeked = service.peek(issued.code());
        assertTrue(peeked.isPresent());
        assertEquals("payload", peeked.get().payload());
        assertTrue(service.peek(issued.code()).isPresent(), "peek 不应消耗验证码");
        assertTrue(service.consume(issued.code()).isPresent(), "peek 后仍可正常消费");
    }

    @Test
    @DisplayName("restore 放回未过期的已消费验证码")
    void restorePutsCodeBack() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        Issued<String> issued = service.issue("payload", Duration.ofSeconds(300));
        assertTrue(service.consume(issued.code()).isPresent());
        assertTrue(service.consume(issued.code()).isEmpty());

        service.restore(issued);
        Optional<Issued<String>> restored = service.consume(issued.code());
        assertTrue(restored.isPresent(), "restore 后验证码可再次消费");
        assertEquals("payload", restored.get().payload());
    }

    @Test
    @DisplayName("restore 不放回过期验证码")
    void restoreRejectsExpired() throws Exception {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(6, false, 0, 0));
        Issued<String> issued = service.issue("payload", Duration.ofSeconds(0));
        assertTrue(service.consume(issued.code()).isEmpty(), "过期码消费即空");
        Thread.sleep(10);
        service.restore(issued);
        assertTrue(service.consume(issued.code()).isEmpty(), "过期验证码不应被放回");
    }

    @Test
    @DisplayName("冷却：原子获取一次，剩余秒数正确")
    void cooldownSinglePass() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        assertTrue(service.tryAcquireCooldown("key-1"), "首次获取应成功");
        assertTrue(service.isOnCooldown("key-1"));
        assertFalse(service.tryAcquireCooldown("key-1"), "冷却期内第二次获取应失败");
        assertTrue(service.cooldownRemainingSeconds("key-1") > 0);
        assertFalse(service.isOnCooldown("key-2"), "不同 key 互不影响");
    }

    @Test
    @DisplayName("putCooldown 无条件重置冷却（原 QQ 发起路径语义）")
    void putCooldownOverwritesUnconditionally() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        // 无冷却时直接写入
        service.putCooldown("key-a");
        assertTrue(service.isOnCooldown("key-a"));
        // 冷却中无条件覆盖（不返回 false、不拒绝）
        service.putCooldown("key-a");
        assertTrue(service.isOnCooldown("key-a"), "putCooldown 不应被冷却期拒绝");
    }

    @Test
    @DisplayName("cleanupExpired 清理过期验证码与冷却记录")
    void cleanupExpiredRemovesEntries() throws Exception {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(6, false, 0, 0));
        String code = service.issue("payload", Duration.ofSeconds(0)).code();
        service.tryAcquireCooldown("expiring-key");
        Thread.sleep(10);
        service.cleanupExpired();
        assertTrue(service.peek(code).isEmpty(), "过期验证码应被清理");
        assertFalse(service.isOnCooldown("expiring-key"), "过期冷却记录应被清理");
    }

    @Test
    @DisplayName("updateSettings 热替换配置且不丢进行中的验证码")
    void updateSettingsHotReplaceKeepsState() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        Issued<String> issued = service.issue("old", Duration.ofSeconds(300));
        service.updateSettings(new Settings(8, true, 600, 120));
        assertEquals(new Settings(8, true, 600, 120), service.settings());
        assertTrue(service.consume(issued.code()).isPresent(), "热替换后进行中的验证码仍可匹配");
        Issued<String> next = service.issue("new", Duration.ofSeconds(600));
        assertEquals(8, next.code().length(), "新验证码按新长度生成");
        assertTrue(next.code().matches("[0-9A-Z]{8}"), "新验证码按新字符集生成");
    }

    @Test
    @DisplayName("设置钳位：码长 [4,8]，秒数 <0→1 且上限 86400")
    void settingsClamped() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(new Settings(100, false, -5, -1));
        assertEquals(new Settings(8, false, 1, 1), service.settings());

        OneTimeCodeService<String> low = new OneTimeCodeService<>(new Settings(2, false, 999_999, 999_999));
        assertEquals(new Settings(4, false, 86_400, 86_400), low.settings());
    }

    @Test
    @DisplayName("验证码只含配置字符集且长度正确")
    void codeCharsetRespected() {
        OneTimeCodeService<String> digits = new OneTimeCodeService<>(new Settings(4, false, 300, 60));
        String code = digits.issue("p", Duration.ofSeconds(300)).code();
        assertEquals(4, code.length());
        assertTrue(code.matches("[0-9]{4}"), "纯数字模式不得出现字母");

        OneTimeCodeService<String> letters = new OneTimeCodeService<>(new Settings(7, true, 300, 60));
        String mixed = letters.issue("p", Duration.ofSeconds(300)).code();
        assertEquals(7, mixed.length());
        assertTrue(mixed.matches("[0-9A-Z&&[^IO]]{7}"), "字母模式排除易混淆的 I/O");
    }

    @Test
    @DisplayName("ttl 决定 expiresAt")
    void ttlDrivesExpiry() {
        OneTimeCodeService<String> service = new OneTimeCodeService<>(DEFAULT_SETTINGS);
        Issued<String> issued = service.issue("p", Duration.ofSeconds(300));
        Instant now = Instant.now();
        assertTrue(issued.expiresAt().isAfter(now));
        assertTrue(issued.expiresAt().isBefore(now.plusSeconds(301)));
    }
}
