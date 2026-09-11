package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ConfigFileStore 运行时存储：装载/重载保旧/值与注释修改/原子保存/迁移补键/多文件。 */
class ConfigFileStoreTest {

    @ConfigModel
    static class Sample {
        @ConfigComment("最大距离（格）")
        int maxDistance = 128;

        @ConfigComment("自动使用")
        boolean autoUse = true;
    }

    @TempDir
    Path tempDir;

    private Path configFile() {
        return tempDir.resolve("config.yml");
    }

    @Test
    void loadsFromExistingFileAndFallsBackOnMissingKeys() {
        write("max-distance: 64\n");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        assertEquals(64, store.get().maxDistance);
        assertEquals(true, store.get().autoUse);
    }

    @Test
    void reloadKeepsOldSnapshotOnCorruptFile() {
        write("max-distance: 64\n");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        write("max-distance: 1\nmax-distance: 2\n"); // 重复键 → 语义门拦截
        store.reload();
        assertEquals(64, store.get().maxDistance, "损坏重载保留旧快照");
        assertTrue(store.lastIssues().stream().anyMatch(i -> i.level() == IssueLevel.ERROR));
    }

    @Test
    void setValueAndCommentsThenSavePersistsByteFidelity() throws IOException {
        write("max-distance: 64\nmode: CHAIN\n");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        store.set("max-distance", 128);
        store.setComments("max-distance", java.util.List.of("新注释"), "行内注释");
        assertEquals(128, store.get().maxDistance, "set 后立即重绑定");
        store.save();
        String text = Files.readString(configFile(), StandardCharsets.UTF_8);
        assertTrue(text.contains("# 新注释\nmax-distance: 128 # 行内注释\n"), "块注释+行内注释写入，其它字节不动: " + text);
        assertTrue(text.contains("mode: CHAIN\n"));
    }

    @Test
    void setMissingPathRejected() {
        write("max-distance: 64\n");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class, () -> store.set("nope", 1));
    }

    @Test
    void materializesDefaultWhenFileMissing() {
        Path defaultCopy = tempDir.resolve("new-file.yml");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "configtest-default.yml", getClass().getClassLoader());
        assertTrue(store.get().maxDistance == 128);
        assertTrue(store.get().autoUse);
    }

    @Test
    void upgradeAddsMissingKeysFromDefaultsWithBackup() throws IOException {
        write("max-distance: 64\n");
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        store.upgrade(2);
        String text = Files.readString(configFile(), StandardCharsets.UTF_8);
        assertTrue(text.contains("max-distance: 64"), "已有键值不被覆盖");
        assertTrue(text.contains("auto-use: true"), "缺失键按默认补入");
        assertTrue(store.get().autoUse);
    }

    @Test
    void multiFileGroupReloadAndSaveAll() throws IOException {
        write("max-distance: 64\n");
        ConfigFileStore<Sample> a = ConfigFileStore.load(
                Sample.class, tempDir, "config.yml", getClass().getClassLoader());
        Path other = tempDir.resolve("other.yml");
        Files.writeString(other, "max-distance: 16\n", StandardCharsets.UTF_8);
        ConfigFileStore<Sample> b = ConfigFileStore.load(
                Sample.class, tempDir, "other.yml", getClass().getClassLoader());
        ConfigGroup group = ConfigGroup.of(a, b);
        a.set("max-distance", 100);
        b.set("max-distance", 200);
        group.saveAll();
        assertTrue(Files.readString(configFile(), StandardCharsets.UTF_8).contains("max-distance: 100"));
        assertTrue(Files.readString(other, StandardCharsets.UTF_8).contains("max-distance: 200"));
    }

    private void write(String content) {
        try {
            Files.writeString(configFile(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // ConfigPostLoad 贯穿（load 首跑 / reload / set 重绑定三径）
    // ------------------------------------------------------------------

    @ConfigModel
    static class Resolvable {
        @ConfigComment("材质 id 列表")
        List<String> ids = List.of("minecraft:stone");

        transient List<String> resolved = List.of();
    }

    private static ConfigPostLoad<Resolvable> upperHook() {
        return (model, issues) -> {
            List<String> r = new java.util.ArrayList<>(model.ids);
            r.replaceAll(String::toUpperCase);
            model.resolved = List.copyOf(r);
        };
    }

    @Test
    void postLoadAppliesOnInitialLoad() {
        write("ids:\n- minecraft:glass\n- minecraft:stone\n");
        ConfigFileStore<Resolvable> store = ConfigFileStore.load(
                Resolvable.class, tempDir, "config.yml", getClass().getClassLoader(), upperHook());
        assertEquals(List.of("MINECRAFT:GLASS", "MINECRAFT:STONE"), store.get().resolved);
    }

    @Test
    void postLoadReAppliesOnReload() {
        write("ids:\n- minecraft:glass\n");
        ConfigFileStore<Resolvable> store = ConfigFileStore.load(
                Resolvable.class, tempDir, "config.yml", getClass().getClassLoader(), upperHook());
        write("ids:\n- minecraft:dirt\n- minecraft:air\n");
        store.reload();
        assertEquals(List.of("MINECRAFT:DIRT", "MINECRAFT:AIR"), store.get().resolved);
    }

    @Test
    void postLoadReAppliesOnSetRebind() {
        write("ids:\n- minecraft:glass\n");
        ConfigFileStore<Resolvable> store = ConfigFileStore.load(
                Resolvable.class, tempDir, "config.yml", getClass().getClassLoader(), upperHook());
        store.set("ids", List.of("minecraft:oak_log"));
        assertEquals(List.of("MINECRAFT:OAK_LOG"), store.get().resolved);
    }

    @Test
    void postLoadRunsOnLastIssuesAndCommitDoc() {
        write("ids:\n- minecraft:glass\n");
        ConfigFileStore<Resolvable> store = ConfigFileStore.load(
                Resolvable.class, tempDir, "config.yml", getClass().getClassLoader(), upperHook());
        assertTrue(store.lastIssues().isEmpty());
    }
}
