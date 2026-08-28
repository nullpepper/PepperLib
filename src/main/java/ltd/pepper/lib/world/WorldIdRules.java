package ltd.pepper.lib.world;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 世界业务标识校验规则（包私有）：id 只允许小写字母/数字/下划线/连字符。
 *
 * <p>保守子集约束：{@code instanceId} 与模板 {@code id} 会出现在 Bukkit 世界名、
 * 日志与缓存键中，禁止路径分隔符、大写（世界名大小写不敏感易冲突）与空白。</p>
 */
final class WorldIdRules {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]+");

    private static final int MAX_ID_LENGTH = 64;

    private WorldIdRules() {}

    static void requireValidId(final String id, final String field) {
        Objects.requireNonNull(id, field);
        if (id.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_ID_LENGTH + " chars: " + id);
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(field + " must match [a-z0-9_-]+ (got: '" + id + "')");
        }
    }
}
