package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 设计文档 §9 Values：与 treecut 私有助手同形，类型不匹配静默回落默认值；enumOf 记 WARN。 */
class ValuesTest {

    private static final Map<String, Object> M = map(
            "b",
            Boolean.TRUE,
            "i",
            42,
            "l",
            9000000000L,
            "d",
            3.5,
            "s",
            "hello",
            "list",
            Arrays.asList("a", 1, null, "b"),
            "enum",
            "GRAVITY");

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void typedGettersReturnValues() {
        assertEquals(true, Values.boolOf(M, "b", false));
        assertEquals(42, Values.intOf(M, "i", 0));
        assertEquals(9000000000L, Values.longOf(M, "l", 0L));
        assertEquals(3.5, Values.doubleOf(M, "d", 0.0));
        assertEquals("hello", Values.stringOf(M, "s", "def"));
        assertEquals(List.of("a", "1", "b"), Values.stringListOf(M.get("list")));
    }

    @Test
    void missingKeysFallBackToDefault() {
        assertEquals(false, Values.boolOf(M, "missing", false));
        assertEquals(7, Values.intOf(M, "missing", 7));
        assertEquals("def", Values.stringOf(M, "missing", "def"));
        assertEquals(List.of(), Values.stringListOf(M.get("missing")));
    }

    @Test
    void typeMismatchFallsBackSilently() {
        // 标量段下读到别的类型（如 string 当数字）→ 默认值，不抛
        assertEquals(0, Values.intOf(M, "s", 0));
        assertEquals(false, Values.boolOf(M, "i", false));
        assertEquals("42", Values.stringOf(M, "i", "def"));
    }

    @Test
    void scalarListValueNormalizedToSingleElement() {
        assertEquals(List.of("abc"), Values.stringListOf("abc"));
    }

    @Test
    void nullValueListBecomesEmpty() {
        Map<String, Object> m = map("k", null);
        assertEquals(List.of(), Values.stringListOf(m.get("k")));
    }

    @Test
    void enumOfResolvesAndWarnsOnUnknown() {
        IssueCollector issues = new IssueCollector();
        assertEquals(FellMode.GRAVITY, Values.enumOf(FellMode.class, M, "enum", FellMode.CHAIN, issues));
        assertFalse(issues.hasErrors());
        assertEquals(0, issues.issues().size());

        Map<String, Object> bad = map("enum", "NOPE");
        IssueCollector issues2 = new IssueCollector();
        assertEquals(FellMode.CHAIN, Values.enumOf(FellMode.class, bad, "enum", FellMode.CHAIN, issues2));
        assertEquals(1, issues2.count(IssueLevel.WARN));
        assertEquals("enum", issues2.issues().get(0).path());
        assertTrue(issues2.issues().get(0).message().contains("NOPE"));
    }

    enum FellMode {
        CHAIN,
        GRAVITY
    }
}
