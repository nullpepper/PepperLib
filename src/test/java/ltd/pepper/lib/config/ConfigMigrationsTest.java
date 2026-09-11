package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ConfigMe 对齐（0.14.0）：可插拔迁移服务（MigrationService/PlainMigrationService 显式接口形态）。 */
class ConfigMigrationsTest {

    @ConfigModel
    static class C {
        int a = 1;
        String b = "x";
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ConfigValues valuesOf(Map<String, Object> root) {
        return Bindings.loadWithValues(C.class, root, new IssueCollector()).values();
    }

    @Test
    void noopNeverMigrates() {
        Map<String, Object> root = map("configVersion", 1, "a", 2);
        assertFalse(ConfigMigrations.noop().checkAndMigrate(root, valuesOf(root)));
        assertEquals(2, root.get("a"));
    }

    @Test
    void versionedRunsStepsAndReportsMigration() {
        Map<String, Object> root = map("configVersion", 1, "a", 2);
        ConfigMigration migration = ConfigMigrations.versioned(
                3, Map.<Integer, java.util.function.UnaryOperator<Map<String, Object>>>of(1, r -> {
                    r.put("newKey", "migrated");
                    r.put("configVersion", 2);
                    return r;
                }));
        boolean changed = migration.checkAndMigrate(root, valuesOf(root));
        assertTrue(changed);
        assertEquals("migrated", root.get("newKey"));
        assertEquals(2, root.get("configVersion"));
    }

    @Test
    void versionedNoStepsReportsNoMigration() {
        Map<String, Object> root = map("configVersion", 1, "a", 2);
        ConfigMigration migration = ConfigMigrations.versioned(3, Map.of());
        assertFalse(migration.checkAndMigrate(root, valuesOf(root)));
    }

    @Test
    void versionedWithValidityTriggersOnInvalidValueEvenWithoutSteps() {
        // 值不合法（a 存在但类型不符）→ 对齐 PlainMigrationService：!areAllValuesValidInResource → 需迁移
        Map<String, Object> root = map("a", "not-a-number");
        ConfigMigration migration = ConfigMigrations.versionedWithValidity(1, Map.of());
        assertTrue(migration.checkAndMigrate(root, valuesOf(root)));
    }

    @Test
    void versionedWithValidityDoesNotTriggerWhenAllValid() {
        Map<String, Object> root = map("a", 2, "b", "ok");
        ConfigMigration migration = ConfigMigrations.versionedWithValidity(1, Map.of());
        assertFalse(migration.checkAndMigrate(root, valuesOf(root)));
    }

    @Test
    void versionedWithValidityTriggersWhenMissingRequiredKeys() {
        Map<String, Object> root = map("a", 2); // b 缺失
        ConfigMigration migration = ConfigMigrations.versionedWithValidity(1, Map.of());
        assertTrue(migration.checkAndMigrate(root, valuesOf(root)));
    }

    @Test
    void customMigrationReceivesValuesForDecision() {
        ConfigMigration custom = (root, values) -> {
            // "值不合法才补默认键" 的领域裁决
            return values.status("b") == ConfigValues.Status.MISSING;
        };
        Map<String, Object> root = map("a", 2);
        assertTrue(custom.checkAndMigrate(root, valuesOf(root)));
        Map<String, Object> root2 = map("a", 2, "b", "ok");
        assertFalse(custom.checkAndMigrate(root2, valuesOf(root2)));
    }
}
