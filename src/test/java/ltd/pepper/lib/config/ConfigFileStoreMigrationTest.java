package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ConfigMe 对齐（0.14.0）：ConfigFileStore × ConfigMigration 集成（保守：只改内存不自动落盘）。 */
class ConfigFileStoreMigrationTest {

    @ConfigModel
    static class C {
        int a = 1;
        String b = "x";
    }

    @ConfigModel
    static class D {
        int a = 1;

        @ConfigModel
        static class Sub {
            int x = 1;
        }

        Sub sub = new Sub();
    }

    /** 版本迁移：1 → 2 时补 b 默认值。 */
    private static final ConfigMigration MIGRATION = ConfigMigrations.versioned(
            2, Map.<Integer, java.util.function.UnaryOperator<Map<String, Object>>>of(1, root -> {
                root.put("configVersion", 2);
                root.putIfAbsent("b", "migrated-default");
                return root;
            }));

    @TempDir
    Path dir;

    @Test
    void loadWithMigrationShapesModelAndReportsMigratedButKeepsDiskUntouched() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader(), MIGRATION);
        assertTrue(store.migrated(), "迁移应发生");
        assertEquals(5, store.get().a);
        assertEquals("migrated-default", store.get().b, "模型应反映迁移后的值");
        // 文档 = 磁盘原文（保守：迁移只改内存，绝不自动落盘）
        assertEquals("a: 5\n", store.text());
        // 值级信号反映迁移后状态
        assertEquals(ConfigValues.Status.PRESENT, store.values().status("b"));
        // save() 也只写当前文档（磁盘原文）——落盘由调用方显式 set/upgrade
        store.save();
        assertEquals("a: 5\n", Files.readString(file));
    }

    @Test
    void reloadAppliesMigrationAndDropsFlagWhenNoLongerNeeded() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader(), MIGRATION);
        assertTrue(store.migrated());
        // 管理员补齐版本后 reload：迁移不再触发
        Files.writeString(file, "a: 6\nb: kept\nconfigVersion: 2\n");
        store.reload();
        assertFalse(store.migrated());
        assertEquals(6, store.get().a);
        assertEquals("kept", store.get().b);
    }

    @Test
    void loadWithoutMigrationBehavesAsBefore() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader());
        assertFalse(store.migrated());
        assertEquals(5, store.get().a);
        assertEquals("x", store.get().b);
    }

    @Test
    void storeValuesExposesInvalidStatusThatIssuesDoNotCover() throws IOException {
        // 类型不符静默回落（§9.5 不记 issue），但 ConfigValues 信号可读 → 迁移决策通道
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: not-a-number\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader());
        assertEquals(ConfigValues.Status.INVALID, store.values().status("a"));
        assertEquals(1, store.get().a);
        assertTrue(store.lastIssues().isEmpty(), "类型不符静默（无 issue），信号在 ConfigValues");
    }

    @Test
    void corruptFileWithMigrationFallsBackToDefaults() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: [broken\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader(), MIGRATION);
        assertFalse(store.migrated());
        assertEquals(1, store.get().a);
        assertEquals("x", store.get().b);
        assertTrue(store.lastIssues().stream().anyMatch(i -> i.level() == IssueLevel.ERROR));
    }

    @Test
    void customMigrationCanUseStoreValuesSignal() throws IOException {
        // 领域裁决："b 缺失才触发迁移"（对齐 PlainMigrationService 的 performMigrations + 值裁决）
        ConfigMigration custom = (root, values) -> values.status("b") == ConfigValues.Status.MISSING;
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\n");
        ConfigFileStore<C> store =
                ConfigFileStore.load(C.class, dir, "config.yml", getClass().getClassLoader(), custom);
        assertTrue(store.migrated());
    }

    @Test
    void migrationReturningFalseLeavesModelUntouchedForNestedMutations() throws IOException {
        // 迁移在嵌套节上做了修改但裁决返回 false → 深副本隔离，绑定源不受污染
        ConfigMigration stretchy = (root, values) -> {
            Map<?, ?> sub = (Map<?, ?>) root.get("sub");
            if (sub != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) sub;
                m.put("x", 999);
            }
            return false;
        };
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\nsub:\n  x: 7\n");
        ConfigFileStore<D> store =
                ConfigFileStore.load(D.class, dir, "config.yml", getClass().getClassLoader(), stretchy);
        assertFalse(store.migrated());
        assertEquals(7, store.get().sub.x, "返回 false 的迁移不得污染绑定源");
    }

    @Test
    void migrationReturningTrueAppliesNestedMutationsToModel() throws IOException {
        ConfigMigration renamer = (root, values) -> {
            Map<?, ?> sub = (Map<?, ?>) root.get("sub");
            if (sub != null && sub.containsKey("x")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) sub;
                m.put("x", 42);
                return true;
            }
            return false;
        };
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "sub:\n  x: 7\n");
        ConfigFileStore<D> store =
                ConfigFileStore.load(D.class, dir, "config.yml", getClass().getClassLoader(), renamer);
        assertTrue(store.migrated());
        assertEquals(42, store.get().sub.x);
    }
}
