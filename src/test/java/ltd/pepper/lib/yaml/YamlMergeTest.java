package ltd.pepper.lib.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 设计文档 §8 写回（文本模板合并）金样测试：磁盘字节原样 + 只插缺失键块（逐字断言）。 */
class YamlMergeTest {

    private static YamlMerge.Result merge(String disk, String tpl) {
        return YamlMerge.merge(disk, tpl);
    }

    @Test
    void appendsMissingTopLevelKeyWithComments() {
        String disk = "a: 1\nb: 2\n";
        String tpl = "# 新键注释\nc: 3\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("a: 1\nb: 2\n\n# 新键注释\nc: 3\n", r.merged());
        assertTrue(r.changed());
        assertEquals(List.of("c"), r.insertedPaths());
    }

    @Test
    void leavesExistingKeyUntouchedEvenIfValueDiffers() {
        String disk = "a: 1\nc: 99\n";
        String tpl = "# 新键注释\nc: 3\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals(disk, r.merged());
        assertFalse(r.changed());
        assertTrue(r.insertedPaths().isEmpty());
    }

    @Test
    void leavesExistingKeyUntouchedOnTypeMismatch() {
        // 磁盘 c 是标量，模板 c 是映射 —— putIfAbsent：已有键不动
        String disk = "c: false\n";
        String tpl = "c:\n  enabled: true\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals(disk, r.merged());
        assertFalse(r.changed());
    }

    @Test
    void insertsMissingKeyInsideExistingSection() {
        String disk = "gate:\n  mode: BLACKLIST\n";
        String tpl = "gate:\n  mode: BLACKLIST\n  # 新键注释\n  axes: [minecraft:oak]\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("gate:\n  mode: BLACKLIST\n  # 新键注释\n  axes: [minecraft:oak]\n", r.merged());
        assertEquals(List.of("gate.axes"), r.insertedPaths());
    }

    @Test
    void insertsMultipleMissingKeysInTemplateOrder() {
        String disk = "gate:\n  mode: BLACKLIST\n";
        String tpl = "gate:\n  mode: BLACKLIST\n  # 键 1 注释\n  key1: 1\n  # 键 2 注释\n  key2: 2\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("gate:\n  mode: BLACKLIST\n  # 键 1 注释\n  key1: 1\n  # 键 2 注释\n  key2: 2\n", r.merged());
        assertEquals(List.of("gate.key1", "gate.key2"), r.insertedPaths());
    }

    @Test
    void appendsWholeMissingSectionWithNestedKeys() {
        String disk = "a: 1\n";
        String tpl = "# 段注释\nb:\n  x: 1\n  y: 2\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("a: 1\n\n# 段注释\nb:\n  x: 1\n  y: 2\n", r.merged());
        assertEquals(List.of("b"), r.insertedPaths());
    }

    @Test
    void insertsIntoSectionsWithEmptyValue() {
        String disk = "gate:\nd: 1\n";
        String tpl = "gate:\n  # 加注释\n  enabled: true\nd: 1\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("gate:\n  # 加注释\n  enabled: true\nd: 1\n", r.merged());
    }

    @Test
    void insertsMultilineFlowListBlockVerbatim() {
        String disk = "profiles:\n  abc:\n    wood: [minecraft:oak_log]\n";
        String tpl = "profiles:\n  abc:\n    wood: [minecraft:oak_log]\n"
                + "    # 土壤注释\n    soil: [\"#minecraft:dirt\",\n"
                + "      minecraft:grass_block,\n      minecraft:dirt]\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals(
                "profiles:\n  abc:\n    wood: [minecraft:oak_log]\n"
                        + "    # 土壤注释\n    soil: [\"#minecraft:dirt\",\n"
                        + "      minecraft:grass_block,\n      minecraft:dirt]\n",
                r.merged());
    }

    @Test
    void goldenRealCorpusExcerptKeepsExistingBytes() {
        String disk = "# 顶部注释\n"
                + "configVersion: 2\n"
                + "gate:\n"
                + "  axes: ['#minecraft:axes']  # 对齐注释\n"
                + "  artificialBlocks: []\n"
                + "effects:\n"
                + "  enabled: true\n"
                + "list:\n"
                + "  - a\n"
                + "  - b\n";
        String tpl = disk + "# 新键注释\nmetrics:\n  enabled: true\n";
        YamlMerge.Result r = merge(disk, tpl);
        String expected = disk + "\n# 新键注释\nmetrics:\n  enabled: true\n";
        assertEquals(expected, r.merged());
        assertEquals(List.of("metrics"), r.insertedPaths());
    }

    @Test
    void emptyDiskYieldsWholeTemplate() {
        String tpl = "# 段注释\nb:\n  x: 1\n";
        YamlMerge.Result r = merge("", tpl);
        assertEquals("# 段注释\nb:\n  x: 1\n", r.merged());
        assertEquals(List.of("b"), r.insertedPaths());
    }

    @Test
    void diskWithoutTrailingNewlineStillAppendsCleanly() {
        String disk = "a: 1";
        String tpl = "# 新键注释\nb: 2\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("a: 1\n\n# 新键注释\nb: 2\n", r.merged());
    }

    @Test
    void nestedMissingOnlyWhenParentExistsInDisk() {
        // 磁盘整个 section 缺失 → 顶层整块插入，不单独插子键
        String disk = "a: 1\n";
        String tpl = "b:\n  x: 1\n  y: 2\n";
        YamlMerge.Result r = merge(disk, tpl);
        assertEquals("a: 1\n\nb:\n  x: 1\n  y: 2\n", r.merged());
        assertEquals(List.of("b"), r.insertedPaths());
    }

    @Test
    void invalidDiskThrowsParsingException() {
        assertThrows(YamlParseException.class, () -> merge("a: 1\na: 2\n", "# c\nc: 3\n"));
    }
}
