package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ltd.pepper.lib.yaml.YamlMerge;
import org.junit.jupiter.api.Test;

/** 设计文档 §8/§9 UpgradePatch：升级补键 = YamlMerge 之上的薄层（plan 纯内存 / apply 出合并文本）。 */
class UpgradePatchTest {

    @Test
    void planReportsMissingPathsWithoutOverwrite() {
        String disk = "a: 1\nb: 99\n";
        String tpl = "a: 1\nb: 3\n# 新键注释\nc:\n  x: 1\n";
        UpgradePatch.Plan p = UpgradePatch.plan(disk, tpl);
        assertTrue(p.changed());
        assertEquals(List.of("c"), p.missingPaths());
    }

    @Test
    void applyMergesAndKeepsExistingBytes() {
        String disk = "a: 1\n";
        String tpl = "a: 1\n# 新键注释\nb: 2\n";
        YamlMerge.Result r = UpgradePatch.apply(disk, tpl);
        assertTrue(r.changed());
        assertEquals("a: 1\n\n# 新键注释\nb: 2\n", r.merged());
        assertEquals(List.of("b"), r.insertedPaths());
    }

    @Test
    void applyIsNoOpWhenNothingMissing() {
        String disk = "a: 1\n";
        String tpl = "a: 1\n";
        YamlMerge.Result r = UpgradePatch.apply(disk, tpl);
        assertFalse(r.changed());
        assertEquals(disk, r.merged());
    }
}
