package ltd.pepper.lib.verification;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次性验证码服务（源自 PepperBotBindManager {@code VerificationManager} 提取）。
 *
 * <p>通用能力：</p>
 * <ul>
 *   <li>{@link #issue} 按字符集/长度生成唯一验证码并绑定负载，返回 {@link Issued}
 *       （含过期时间）；</li>
 *   <li>{@link #consume} 原子消费（{@code remove} 语义）——并发同码只有一次成功；
 *       过期验证码消费为空且不再可用；</li>
 *   <li>{@link #peek} 非破坏性查看（白名单复用、有效性预检）；</li>
 *   <li>{@link #restore} 放回未过期的已消费验证码（绑定失败回滚场景）；</li>
 *   <li>{@link #tryAcquireCooldown} 每键冷却原子获取（检查与写入在
 *       {@code compute} 内完成）；</li>
 *   <li>{@link #cleanupExpired} 清理过期验证码与冷却记录；</li>
 *   <li>{@link #updateSettings} 热替换配置，进行中的验证码/冷却状态不受影响。</li>
 * </ul>
 *
 * <p>负载类型由调用方决定（如绑定请求、Discord 绑定记录），本服务不感知领域语义。
 * 所有设置项在构造与热替换时钳位（码长 [4,8]；秒数 &lt;0 → 1，上限 86400；
 * 0 保留为「立即过期/无冷却」语义，仅测试构造直接使用）。</p>
 *
 * @param <T> 验证码绑定的负载类型
 */
public final class OneTimeCodeService<T> {

    private static final String DIGITS = "0123456789";
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS_AND_LETTERS = DIGITS + LETTERS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int MIN_CODE_LENGTH = 4;
    private static final int MAX_CODE_LENGTH = 8;
    private static final long MAX_SECONDS = 86_400L;

    /** 可热替换的设置（构造与 {@link #updateSettings} 均钳位）。 */
    public record Settings(int codeLength, boolean useLetters, long expirySeconds, long cooldownSeconds) {}

    /** 一次签发的完整结果：验证码、负载与过期时间。 */
    public record Issued<T>(String code, T payload, Instant expiresAt) {}

    private final Map<String, Issued<T>> codeIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    private volatile int codeLength;
    private volatile boolean useLetters;
    private volatile long expirySeconds;
    private volatile long cooldownSeconds;

    /** @param settings 初始设置（非法值钳位，见类注释） */
    public OneTimeCodeService(Settings settings) {
        updateSettings(settings);
    }

    /** 热替换配置；进行中的验证码与冷却状态不重建。 */
    public void updateSettings(Settings settings) {
        this.codeLength = clampCodeLength(settings.codeLength());
        this.useLetters = settings.useLetters();
        this.expirySeconds = clampSeconds(settings.expirySeconds());
        this.cooldownSeconds = clampSeconds(settings.cooldownSeconds());
    }

    /** 当前生效设置（钳位后）。 */
    public Settings settings() {
        return new Settings(codeLength, useLetters, expirySeconds, cooldownSeconds);
    }

    /**
     * 签发一次性验证码。
     *
     * @param payload 负载（不可为 null）
     * @param ttl     有效期（不可为 null；零/负值即立即过期）
     * @return 签发结果（含验证码与过期时间）
     */
    public Issued<T> issue(T payload, Duration ttl) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        String code = generateCode();
        Issued<T> issued = new Issued<>(code, payload, Instant.now().plus(ttl));
        codeIndex.put(code, issued);
        return issued;
    }

    /**
     * 原子消费验证码：并发同码只有一次成功；过期/未知验证码返回空。
     * 消费成功的验证码从注册表移除（含过期后消费——过期码同样不再可用）。
     */
    public Optional<Issued<T>> consume(String code) {
        Issued<T> issued = codeIndex.remove(code);
        if (issued == null || Instant.now().isAfter(issued.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(issued);
    }

    /** 非破坏性查看：验证码仍保留，可再次消费；过期/未知返回空。 */
    public Optional<Issued<T>> peek(String code) {
        Issued<T> issued = codeIndex.get(code);
        if (issued == null || Instant.now().isAfter(issued.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(issued);
    }

    /** 放回未过期的已消费验证码（绑定/操作失败回滚）；过期或空参数忽略。 */
    public void restore(Issued<T> issued) {
        if (issued == null || issued.code() == null || Instant.now().isAfter(issued.expiresAt())) {
            return;
        }
        codeIndex.put(issued.code(), issued);
    }

    /**
     * 每键冷却原子获取：检查与写入在 {@code compute} 内完成，并发同 key 只放行一次。
     *
     * @param key 冷却键（如 {@code "game:&lt;uuid&gt;"}）
     * @return 是否成功获取（false = 冷却中）
     */
    public boolean tryAcquireCooldown(String key) {
        long now = System.currentTimeMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        cooldowns.compute(key, (k, expiry) -> {
            if (expiry != null && expiry > now) {
                return expiry; // 冷却中：不改写
            }
            acquired.set(true);
            return now + cooldownSeconds * 1000;
        });
        return acquired.get();
    }

    /**
     * 无条件写入冷却（覆盖式重置）：无论当前是否冷却，从此刻起重新计时。
     * 用于「调用方已完成前置冷却检查」的路径，语义与直接 put 一致。
     */
    public void putCooldown(String key) {
        cooldowns.put(key, System.currentTimeMillis() + cooldownSeconds * 1000);
    }

    /** 是否处于冷却期（过期记录惰性移除）。 */
    public boolean isOnCooldown(String key) {
        Long expiry = cooldowns.get(key);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() < expiry) {
            return true;
        }
        cooldowns.remove(key);
        return false;
    }

    /** 冷却剩余秒数（未冷却/无记录返回 0）。 */
    public long cooldownRemainingSeconds(String key) {
        Long expiry = cooldowns.get(key);
        if (expiry == null) {
            return 0;
        }
        return Math.max(0, (expiry - System.currentTimeMillis()) / 1000);
    }

    /** 清理过期验证码与过期冷却记录（定时任务入口）。 */
    public void cleanupExpired() {
        Instant now = Instant.now();
        codeIndex.values().removeIf(issued -> now.isAfter(issued.expiresAt()));
        cooldowns.values().removeIf(expiry -> System.currentTimeMillis() >= expiry);
    }

    private static int clampCodeLength(int value) {
        return Math.max(MIN_CODE_LENGTH, Math.min(MAX_CODE_LENGTH, value));
    }

    private static long clampSeconds(long value) {
        if (value < 0) {
            return 1L;
        }
        return Math.min(MAX_SECONDS, value);
    }

    private String generateCode() {
        // 用 do...while 循环代替递归，避免极端情况下栈溢出
        String charset = useLetters ? DIGITS_AND_LETTERS : DIGITS;
        String code;
        do {
            StringBuilder sb = new StringBuilder(codeLength);
            for (int i = 0; i < codeLength; i++) {
                sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
            }
            code = sb.toString();
        } while (codeIndex.containsKey(code));
        return code;
    }
}
