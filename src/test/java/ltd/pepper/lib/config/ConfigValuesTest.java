package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ConfigMe 对齐（0.14.0）：值级合法性信号（PropertyValue.isValidInResource 二元 → 三态）。 */
class ConfigValuesTest {

    private enum Mode {
        CHAIN,
        GRAVITY
    }

    @ConfigModel
    static class C {
        int a = 1;
        String b = "x";
        Mode mode = Mode.CHAIN;
        List<String> list = List.of();
        Optional<String> opt = Optional.empty();
    }

    @ConfigModel
    static class Ranged {
        @ConfigRange(min = 0, max = 10, clamp = true)
        int n = 5;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void emptyResourceMarksRequiredKeysMissingAndOptionalPresent() {
        ConfigValues v =
                Bindings.loadWithValues(C.class, map(), new IssueCollector()).values();
        assertEquals(ConfigValues.Status.MISSING, v.status("a"));
        assertEquals(ConfigValues.Status.MISSING, v.status("b"));
        assertEquals(ConfigValues.Status.MISSING, v.status("mode"));
        assertEquals(ConfigValues.Status.MISSING, v.status("list"));
        // Optional 缺失 = 有效表示（对齐 ConfigMe：absent optional 不触发重写）
        assertEquals(ConfigValues.Status.PRESENT, v.status("opt"));
        assertFalse(v.allValidInResource());
        assertEquals(List.of("a", "b", "mode", "list"), v.missingKeys());
        assertEquals(List.of(), v.invalidKeys());
    }

    @Test
    void validResourceMarksEverythingPresent() {
        ConfigValues v = Bindings.loadWithValues(
                        C.class,
                        map("a", 2, "b", "y", "mode", "GRAVITY", "list", List.of("q"), "opt", "z"),
                        new IssueCollector())
                .values();
        assertTrue(v.allValidInResource());
        assertEquals(List.of(), v.missingKeys());
        assertEquals(List.of(), v.invalidKeys());
        // 值可查（解析后值 + 状态）
        assertEquals(2, v.entry("a").value());
        assertEquals("y", v.entry("b").value());
        assertEquals(Mode.GRAVITY, v.entry("mode").value());
        assertEquals(Optional.of("z"), v.entry("opt").value());
    }

    @Test
    void invalidValuesMarkedInvalid() {
        ConfigValues v = Bindings.loadWithValues(
                        C.class, map("a", "not-a-number", "mode", "bogus", "b", "ok"), new IssueCollector())
                .values();
        assertEquals(ConfigValues.Status.INVALID, v.status("a"));
        assertEquals(ConfigValues.Status.INVALID, v.status("mode"));
        assertEquals(ConfigValues.Status.PRESENT, v.status("b"));
        // 类型不符静默回落（§9.5 不记 issue），但状态信号仍可读
        assertFalse(v.allValidInResource());
        assertEquals(List.of("a", "mode"), v.invalidKeys());
        // 回落值 = 默认
        assertEquals(1, v.entry("a").value());
        assertEquals(Mode.CHAIN, v.entry("mode").value());
    }

    @Test
    void clampMarksValueInvalidSinceResourceWasRewritten() {
        ConfigValues v = Bindings.loadWithValues(Ranged.class, map("n", 99), new IssueCollector())
                .values();
        assertEquals(ConfigValues.Status.INVALID, v.status("n"));
        assertEquals(10, v.entry("n").value());
    }

    @Test
    void loadWithValuesModelMatchesPlainLoad() {
        Map<String, Object> raw = map("a", 7, "b", "hello");
        IssueCollector issues1 = new IssueCollector();
        IssueCollector issues2 = new IssueCollector();
        C plain = Bindings.load(C.class, raw, issues1);
        Bindings.LoadResult<C> detailed = Bindings.loadWithValues(C.class, raw, issues2);
        assertEquals(plain.a, detailed.model().a);
        assertEquals(plain.b, detailed.model().b);
        assertEquals(issues1.issues(), issues2.issues());
    }

    @Test
    void unknownKeyReturnsMissing() {
        ConfigValues v =
                Bindings.loadWithValues(C.class, map(), new IssueCollector()).values();
        assertEquals(ConfigValues.Status.MISSING, v.status("nope"));
    }
}
