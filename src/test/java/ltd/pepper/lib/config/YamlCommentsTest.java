package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** YamlComments 注释归属解析：条目 → 其上方块注释 + 行内注释。 */
class YamlCommentsTest {

    private static final String TEXT = "# 最大距离（格）\n"
            + "# 超过后不再判定或应用发光\n"
            + "max-distance: 128\n"
            + "auto-use: true # 是否自动使用\n"
            + "\n"
            + "stats:\n"
            + "  # 冷却（毫秒）\n"
            + "  cooldown-ms: 1000\n"
            + "  tick: 0.5\n";

    @Test
    void blockCommentsAboveEntryAttachToIt() {
        var e = YamlComments.commentsAt(TEXT, "max-distance");
        assertEquals(List.of("最大距离（格）", "超过后不再判定或应用发光"), e.blockComments());
        assertNull(e.inlineComment());
    }

    @Test
    void inlineCommentAttachesToEntry() {
        var e = YamlComments.commentsAt(TEXT, "auto-use");
        assertTrue(e.blockComments().isEmpty());
        assertEquals("是否自动使用", e.inlineComment());
    }

    @Test
    void nestedEntryCommentsResolveByDottedPath() {
        var e = YamlComments.commentsAt(TEXT, "stats.cooldown-ms");
        assertEquals(List.of("冷却（毫秒）"), e.blockComments());
        assertNull(e.inlineComment());
    }

    @Test
    void sectionHeaderWithoutCommentHasNone() {
        var e = YamlComments.commentsAt(TEXT, "stats");
        assertTrue(e.blockComments().isEmpty());
        assertNull(e.inlineComment());
    }

    @Test
    void missingPathReturnsNull() {
        assertNull(YamlComments.commentsAt(TEXT, "nope"));
        assertNull(YamlComments.commentsAt(TEXT, "stats.nope"));
    }

    @Test
    void entriesIteratorListsCommentedEntries() {
        List<YamlComments.Entry> all = YamlComments.entries(TEXT);
        assertEquals(3, all.size(), "带注释的叶子条目（含嵌套）都进入列表");
        assertEquals("max-distance", all.get(0).path());
        assertEquals("auto-use", all.get(1).path());
        assertEquals("stats.cooldown-ms", all.get(2).path());
    }
}
