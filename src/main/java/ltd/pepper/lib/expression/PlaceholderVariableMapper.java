package ltd.pepper.lib.expression;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把条件串中的 {@code <xxx>} 与 {@code %xxx%} 改写为合法变量名（非法字符 → _），
 * 并记录 变量名 → 原始占位符名 的映射（运行时按原名解析占位符值）。
 *
 * <p>识别规则：
 * <ul>
 *   <li>{@code <...>} 严格识别（内容须全为字母/数字/下划线）——因为 {@code <} 兼任比较运算符，
 *       如 {@code a < b}、{@code x < 5} 不得误判为占位符；</li>
 *   <li>{@code %...%} 放宽识别（到下一个 % 为止，中间不含空白）——PAPI 占位符可含
 *       冒号、连字符、点等（如 {@code %server_time_HH:mm:ss%}），sanitize 后仍可编译，
 *       运行时按原名经外部解析器（如 PAPI）解析，解析失败时由调用方决定降级策略。</li>
 * </ul>
 */
public final class PlaceholderVariableMapper {

    private PlaceholderVariableMapper() {}

    /**
     * @param expression       改写后的表达式
     * @param builtinVariables 内置变量：变量名 → 原始占位符名（{@code <...>} 内容）
     * @param papiVariables    PAPI 变量：变量名 → 原始占位符名（{@code %...%} 内容）
     */
    public record Mapping(String expression, Map<String, String> builtinVariables, Map<String, String> papiVariables) {

        /** 全部变量名（内置 + PAPI），用于求值时准备变量 Map 与重名检测 */
        public Set<String> allVariables() {
            Set<String> all = new LinkedHashSet<>(builtinVariables.keySet());
            all.addAll(papiVariables.keySet());
            return all;
        }
    }

    private record Token(String raw, boolean builtin) {}

    public static Mapping rewrite(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Mapping(raw == null ? "" : raw, Map.of(), Map.of());
        }
        StringBuilder sb = new StringBuilder(raw.length());
        Map<String, Token> seen = new LinkedHashMap<>();
        Map<String, String> builtin = new LinkedHashMap<>();
        Map<String, String> papi = new LinkedHashMap<>();
        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            int end = -1;
            boolean isBuiltin = false;
            if (c == '<') {
                end = scanBuiltin(raw, i + 1);
                isBuiltin = true;
            } else if (c == '%') {
                end = scanPapi(raw, i + 1);
            }
            if (end > i + 1) {
                String name = raw.substring(i + 1, end);
                String varName = register(seen, name, isBuiltin);
                (isBuiltin ? builtin : papi).putIfAbsent(varName, name);
                sb.append(varName);
                i = end + 1;
                continue;
            }
            sb.append(c);
            i++;
        }
        return new Mapping(sb.toString(), Map.copyOf(builtin), Map.copyOf(papi));
    }

    /** {@code <...>}：内容须全为合法标识符字符，返回 '>' 下标；否则 -1 */
    private static int scanBuiltin(String raw, int from) {
        int j = from;
        while (j < raw.length() && isNameChar(raw.charAt(j))) {
            j++;
        }
        return (j < raw.length() && raw.charAt(j) == '>') ? j : -1;
    }

    /** {@code %...%}：到下一个 % 为止，中间至少 1 字符且不含空白，返回 '%' 下标；否则 -1 */
    private static int scanPapi(String raw, int from) {
        int j = from;
        while (j < raw.length() && raw.charAt(j) != '%') {
            if (Character.isWhitespace(raw.charAt(j))) {
                return -1;
            }
            j++;
        }
        return (j < raw.length() && j > from) ? j : -1;
    }

    /** 注册占位符：检测"清洗后同名"冲突，返回变量名 */
    private static String register(Map<String, Token> seen, String name, boolean builtin) {
        String varName = sanitize(name);
        Token token = new Token(name, builtin);
        Token prev = seen.putIfAbsent(varName, token);
        if (prev != null && !(prev.raw().equals(name) && prev.builtin() == builtin)) {
            throw new IllegalArgumentException("占位符变量名冲突: " + prev.raw() + " 与 " + name + " 都映射为变量 " + varName);
        }
        return varName;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** 非法字符 → _；首字符为数字时前缀 _（标识符不得以数字开头） */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(isNameChar(c) ? c : '_');
        }
        if (sb.length() > 0 && sb.charAt(0) >= '0' && sb.charAt(0) <= '9') {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
