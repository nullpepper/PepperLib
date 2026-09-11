package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ConfigDoc 字节保真编辑：条目值/注释修改只动目标区，其它字节逐字不变。 */
class ConfigDocTest {

    @Test
    void valueEditKeepsBlockAndInlineCommentsAndOtherLines() {
        String input = "# 最大距离（格）\nmax-distance: 128 # 行内注释\nmode: CHAIN\n";
        String out = ConfigDoc.parse(input).withValue("max-distance", 64).text();
        assertEquals("# 最大距离（格）\nmax-distance: 64 # 行内注释\nmode: CHAIN\n", out);
    }

    @Test
    void valueEditQuotesStringsPerYamlRules() {
        String out = ConfigDoc.parse("mode: CHAIN\n").withValue("mode", "A: B").text();
        assertEquals("mode: 'A: B'\n", out);
        String out2 = ConfigDoc.parse("mode: CHAIN\n").withValue("mode", true).text();
        assertEquals("mode: true\n", out2);
    }

    @Test
    void valueEditListBecomesFlowList() {
        String input = "axes: []\n";
        String out = ConfigDoc.parse(input).withValue("axes", List.of("a", "b")).text();
        assertEquals("axes: [a, b]\n", out);
    }

    @Test
    void commentEditReplacesBlockAndInline() {
        String out = ConfigDoc.parse("a: 1\n")
                .withComments("a", List.of("新注释", "第二行"), "行内注释")
                .text();
        assertEquals("# 新注释\n# 第二行\na: 1 # 行内注释\n", out);
    }

    @Test
    void commentEditRemovesExistingInline() {
        String out = ConfigDoc.parse("a: 1 # 旧行内\nb: 2\n")
                .withComments("a", List.of(), null)
                .text();
        assertEquals("a: 1\nb: 2\n", out);
    }

    @Test
    void blockInsertSeparatesWithBlankLine() {
        String out = ConfigDoc.parse("x: 1\na: 2\n")
                .withComments("a", List.of("关于 a"), null)
                .text();
        assertEquals("x: 1\n\n# 关于 a\na: 2\n", out);
    }

    @Test
    void blockReplacePreservesOtherBytes() {
        String input = "# 旧注释 A\n# 旧注释 B\na: 1\n  # 不属于 a 的注释\nb: 2\n";
        String out =
                ConfigDoc.parse(input).withComments("a", List.of("新"), null).text();
        assertEquals("# 新\na: 1\n  # 不属于 a 的注释\nb: 2\n", out);
    }

    @Test
    void missingPathRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> ConfigDoc.parse("a: 1\n").withValue("nope", 1));
    }

    @Test
    void childrenEnumeratesDirectChildren() {
        ConfigDoc doc = ConfigDoc.parse("a: 1\nb:\n  x: 2\n  y: 3\nc: []\n");
        List<ConfigDoc.Entry> root = doc.children("");
        assertEquals(3, root.size());
        assertEquals("a", root.get(0).name());
        assertEquals(1, root.get(0).value());
        assertTrue(!root.get(0).section());
        assertEquals("b", root.get(1).name());
        assertTrue(root.get(1).section());

        List<ConfigDoc.Entry> b = doc.children("b");
        assertEquals(2, b.size());
        assertEquals("x", b.get(0).name());
        assertEquals(2, b.get(0).value());
    }

    @Test
    void childrenOfLeafOrMissingThrows() {
        ConfigDoc doc = ConfigDoc.parse("a: 1\n");
        assertThrows(IllegalArgumentException.class, () -> doc.children("a"));
        assertThrows(IllegalArgumentException.class, () -> doc.children("nope"));
    }

    @Test
    void mergeDefaultsInsertsOnlyMissingKeysByteFidelity() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("keep", 99);
        defaults.put("added", 42);
        defaults.put("flag", true);
        ConfigDoc out = ConfigDoc.parse("a: 1\ns:\n  keep: 1\n").mergeDefaults("s", defaults);
        String text = out.text();
        assertEquals("s:\n  keep: 1\n  added: 42\n  flag: true\n", text.substring(text.indexOf("s:")), text);
        assertEquals("a: 1\n", text.substring(0, text.indexOf("s:")), "其它字节不动");
    }

    @Test
    void mergeDefaultsHandlesNestedSectionDefaults() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("x", 1);
        nested.put("y", "hello");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("newSec", nested);
        ConfigDoc out = ConfigDoc.parse("s:\n  keep: 1\n").mergeDefaults("s", defaults);
        assertEquals("s:\n  keep: 1\n  newSec:\n    x: 1\n    y: hello\n", out.text(), out.text());
    }

    @Test
    void mergeDefaultsIntoRootAppendsAtEnd() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("second", 2);
        ConfigDoc out = ConfigDoc.parse("first: 1\n").mergeDefaults("", defaults);
        assertEquals("first: 1\nsecond: 2\n", out.text());
    }

    @Test
    void mergeDefaultsMissingSectionThrows() {
        assertThrows(
                IllegalArgumentException.class, () -> ConfigDoc.parse("a: 1\n").mergeDefaults("nope", Map.of("x", 1)));
    }

    @Test
    void headerReadsLeadingBlockComment() {
        ConfigDoc doc = ConfigDoc.parse("# 配置说明\n# 第二行\nfirst: 1\n");
        assertEquals(List.of("配置说明", "第二行"), doc.header());
        assertTrue(ConfigDoc.parse("first: 1\n").header().isEmpty());
    }

    @Test
    void withHeaderSetsLeadingCommentByteFidelity() {
        ConfigDoc out = ConfigDoc.parse("first: 1 # 行内\nsecond: 2\n").withHeader(List.of("新头部", "第二段"));
        assertEquals("# 新头部\n# 第二段\nfirst: 1 # 行内\nsecond: 2\n", out.text(), out.text());
    }

    @Test
    void withHeaderReplacesAndClearsExistingHeader() {
        ConfigDoc replaced = ConfigDoc.parse("# 旧头\nfirst: 1\n").withHeader(List.of("新头"));
        assertEquals("# 新头\nfirst: 1\n", replaced.text());
        ConfigDoc cleared = ConfigDoc.parse("# 旧头\nfirst: 1\n").withHeader(List.of());
        assertEquals("first: 1\n", cleared.text());
    }

    @Test
    void valueLookupWorks() {
        ConfigDoc doc = ConfigDoc.parse("a: 1\ns:\n  b: hello\n");
        assertEquals(1, doc.value("a"));
        assertEquals("hello", doc.value("s.b"));
        assertTrue(doc.contains("s.b"));
        assertNull(doc.value("x"));
        assertTrue(!doc.contains("x"));
    }
}
