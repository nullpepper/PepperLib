package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 设计文档 §9 ConfigVersions：版本读取 + 链式迁移（treecut ConfigMigrator 泛化，内存副本上执行）。 */
class ConfigVersionsTest {

    @Test
    void versionOfDefaultsToOneWhenMissing() {
        assertEquals(1, ConfigVersions.versionOf(Map.of()));
        assertEquals(1, ConfigVersions.versionOf(Map.of("configVersion", "abc")));
        assertEquals(2, ConfigVersions.versionOf(Map.of("configVersion", 2)));
    }

    @Test
    void migrateAppliesStepsUntilCurrentVersion() {
        // v1 → v2 删键改名；v2 → v3 加键（每步自增 configVersion）
        Map<String, Object> root = new LinkedHashMap<>(Map.of("configVersion", 1, "oldKey", "x"));
        Map<Integer, UnaryOperator<Map<String, Object>>> steps = Map.of(
                1,
                        r -> {
                            r.remove("oldKey");
                            r.put("newKey", "y");
                            r.put("configVersion", 2);
                            return r;
                        },
                2,
                        r -> {
                            r.put("thirdKey", 3);
                            r.put("configVersion", 3);
                            return r;
                        });

        assertTrue(ConfigVersions.migrate(root, 3, steps));
        assertEquals(3, root.get("configVersion"));
        assertEquals("y", root.get("newKey"));
        assertEquals(3, root.get("thirdKey"));
        assertFalse(root.containsKey("oldKey"));
    }

    @Test
    void migrateReturnsFalseWhenAlreadyCurrent() {
        Map<String, Object> root = new LinkedHashMap<>(Map.of("configVersion", 3));
        assertFalse(ConfigVersions.migrate(root, 3, Map.of()));
        assertEquals(3, root.get("configVersion"));
    }

    @Test
    void migrateStopsWhenStepMissing() {
        Map<String, Object> root = new LinkedHashMap<>(Map.of("configVersion", 1));
        // 只有 1→2 步骤，目标 4：应用一步即停在 2（无 2→3 步骤）
        Map<Integer, UnaryOperator<Map<String, Object>>> steps = Map.of(1, r -> {
            r.put("configVersion", 2);
            return r;
        });
        assertTrue(ConfigVersions.migrate(root, 4, steps));
        assertEquals(2, root.get("configVersion"));
    }

    @Test
    void migrateGuardsAgainstNonBumpingStep() {
        Map<String, Object> root = new LinkedHashMap<>(Map.of("configVersion", 1));
        Map<Integer, UnaryOperator<Map<String, Object>>> steps = Map.of(1, r -> {
            r.put("noop", true);
            return r;
        });
        // 步骤没提升版本——防死循环，应用一次后停止
        assertTrue(ConfigVersions.migrate(root, 9, steps));
        assertEquals(1, root.get("configVersion"));
    }
}
