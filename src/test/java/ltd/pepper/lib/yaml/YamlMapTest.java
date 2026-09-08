package ltd.pepper.lib.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

/** 设计文档 §6 语义定案 S1–S16 的矩阵测试：逐行锁定引擎行为。 */
class YamlMapTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    // ── 标准解析 / 保序（S10）──────────────────────────────────────────────

    @Test
    void parsesNestedMappingAndFlowList() {
        Map<String, Object> m = YamlMap.parse("a:\n  b: 1\n  c: [x, y, 'z z']\n");
        Map<String, Object> a = map(m.get("a"));
        assertEquals(1, a.get("b"));
        assertEquals(List.of("x", "y", "z z"), a.get("c"));
    }

    @Test
    void preservesKeyOrderAsLinkedHashMap() {
        Map<String, Object> m = YamlMap.parse("z: 1\na: 2\nm: 3\n");
        assertInstanceOf(LinkedHashMap.class, m);
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(m.keySet()));
    }

    @Test
    void parsesBlockSequenceOfMappings() {
        Map<String, Object> m = YamlMap.parse("items:\n  - name: a\n    id: 1\n  - name: b\n    id: 2\n");
        List<?> items = (List<?>) m.get("items");
        assertEquals(2, items.size());
        assertEquals("a", map(items.get(0)).get("name"));
        assertEquals(2, map(items.get(1)).get("id"));
    }

    // ── 标量（S4/S5）───────────────────────────────────────────────────────

    @Test
    void resolvesYaml11BooleanVocabulary() {
        Map<String, Object> m = YamlMap.parse("a: on\nb: OFF\nc: yes\nd: omg\n");
        assertEquals(true, m.get("a"));
        assertEquals(false, m.get("b"));
        assertEquals(true, m.get("c"));
        assertEquals("omg", m.get("d"));
    }

    @Test
    void resolvesNumbers() {
        Map<String, Object> m = YamlMap.parse("hex: 0x10\nsep: 1_000\nd: 3.14\ne: 1e3\n");
        assertEquals(16, m.get("hex"));
        assertEquals(1000, m.get("sep"));
        assertEquals(3.14, m.get("d"));
        assertEquals(1000.0, m.get("e"));
    }

    // ── 时间戳禁用（S3）────────────────────────────────────────────────────

    @Test
    void keepsTimestampLikeScalarAsString() {
        Map<String, Object> m = YamlMap.parse("expires: 2026-01-01\n");
        assertEquals("2026-01-01", m.get("expires"));
    }

    // ── 引号/转义/多行/锚点（S11）──────────────────────────────────────────

    @Test
    void parsesQuotedAndEscapedScalars() {
        Map<String, Object> m = YamlMap.parse("a: 'it''s'\nb: \"line1\\nline2\"\nc: '普通文本'\n");
        assertEquals("it's", m.get("a"));
        assertEquals("line1\nline2", m.get("b"));
        assertEquals("普通文本", m.get("c"));
    }

    @Test
    void parsesLiteralBlockScalar() {
        Map<String, Object> m = YamlMap.parse("text: |\n  line1\n  line2\n");
        assertEquals("line1\nline2\n", m.get("text"));
    }

    @Test
    void resolvesAnchorsAndAliases() {
        Map<String, Object> m = YamlMap.parse("base: &b [1, 2]\ncopy: *b\n");
        assertEquals(List.of(1, 2), m.get("copy"));
    }

    // ── 空/注释/空值/多文档/BOM（S6/S9/S12/S13）────────────────────────────

    @Test
    void emptyDocumentAndCommentOnlyGiveEmptyMap() {
        assertTrue(YamlMap.parse("").isEmpty());
        assertTrue(YamlMap.parse("# 只有注释\n# 第二行\n").isEmpty());
    }

    @Test
    void emptyValueIsNull() {
        Map<String, Object> m = YamlMap.parse("k:\n");
        assertNull(m.get("k"));
    }

    @Test
    void rejectsMultiDocumentStream() {
        // S12：规范禁止多文档——Yaml.load 单文档语义，遇 "---" 第二文档直接报错
        assertThrows(YamlParseException.class, () -> YamlMap.parse("a: 1\n---\nb: 2\n"));
    }

    @Test
    void stripsUtf8Bom() {
        Map<String, Object> m = YamlMap.parse("\uFEFFa: 1\n");
        assertEquals(1, m.get("a"));
    }

    // ── 注释（S16：只读路径 processComments=false，注释丢弃）───────────────

    @Test
    void ignoresCommentsInReadOnlyPath() {
        Map<String, Object> m = YamlMap.parse("# 顶部注释\na: 1 # 行尾注释\n");
        assertEquals(1, m.get("a"));
        assertEquals(1, m.size());
    }

    // ── 错误路径（S7/S8/S14/S1/S2）─────────────────────────────────────────

    @Test
    void rejectsTopLevelSequence() {
        YamlParseException e = assertThrows(YamlParseException.class, () -> YamlMap.parse("- 1\n- 2\n"));
        assertTrue(e.problem() != null && !e.problem().isBlank());
    }

    @Test
    void rejectsTopLevelScalar() {
        assertThrows(YamlParseException.class, () -> YamlMap.parse("just a string\n"));
    }

    @Test
    void rejectsDuplicateKeys() {
        YamlParseException e = assertThrows(YamlParseException.class, () -> YamlMap.parse("dup: 1\ndup: 2\n"));
        assertTrue(e.problem() != null && !e.problem().isBlank());
    }

    @Test
    void rejectsTabIndentation() {
        assertThrows(YamlParseException.class, () -> YamlMap.parse("a: 1\n\tb: 2\n"));
    }

    @Test
    void rejectsUnsafeTag() {
        // S1：SafeConstructor 拒绝全局/自定义 tag 构造任意类
        assertThrows(YamlParseException.class, () -> YamlMap.parse("x: !!java.lang.Thread {}\n"));
    }

    @Test
    void rejectsAliasBomb() {
        // S2：集合别名上限（默认 50）防护——60 个别名应被拒绝
        StringBuilder sb = new StringBuilder("a: &x [0]\n");
        for (int i = 0; i < 60; i++) {
            sb.append("k").append(i).append(": *x\n");
        }
        assertThrows(YamlParseException.class, () -> YamlMap.parse(sb.toString()));
    }

    @Test
    void reportsLineAndColumnOnSyntaxError() {
        // 未闭合 flow list → 带位置信息的解析错误
        YamlParseException e = assertThrows(YamlParseException.class, () -> YamlMap.parse("a: [1, 2\n"));
        assertTrue(e.line() >= 1, "line 应为 1-based 且 ≥1，实际 " + e.line());
        assertTrue(e.column() >= 1);
        assertTrue(e.problem() != null && !e.problem().isBlank());
        assertInstanceOf(YAMLException.class, e.getCause());
    }

    // ── 契约 ───────────────────────────────────────────────────────────────

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> YamlMap.parse(null));
    }
}
