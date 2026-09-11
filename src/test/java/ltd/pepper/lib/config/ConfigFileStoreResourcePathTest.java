package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * store 的两条路径解耦：classpath 默认资源可在子目录（如 {@code defaults/config.yml}），
 * 而数据目录文件名保持运维可见的 {@code config.yml}。
 */
class ConfigFileStoreResourcePathTest {

    @ConfigModel
    static class Sample {
        @ConfigComment("最大距离（格）")
        int maxDistance = 128;

        @ConfigComment("自动使用")
        boolean autoUse = true;
    }

    @TempDir
    Path tempDir;

    @Test
    void materializesFromClasspathSubfolderIntoDataFolderFile() throws IOException {
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class,
                tempDir,
                "config.yml",
                "defaults/nested-sample.yml",
                getClass().getClassLoader());
        Path materialized = tempDir.resolve("config.yml");
        assertTrue(Files.exists(materialized), "应把子目录默认资源材质化到数据目录根");
        assertTrue(Files.readString(materialized, StandardCharsets.UTF_8).contains("# 嵌套子目录默认资源"), "材质化须逐字保留资源里的注释");
        assertEquals(256, store.get().maxDistance);
        assertEquals(false, store.get().autoUse);
        assertEquals("defaults/nested-sample.yml", store.resourcePath());
    }

    @Test
    void existingDiskFileWinsOverBundledResource() throws IOException {
        Files.writeString(tempDir.resolve("config.yml"), "max-distance: 7\n", StandardCharsets.UTF_8);
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class,
                tempDir,
                "config.yml",
                "defaults/nested-sample.yml",
                getClass().getClassLoader());
        assertEquals(7, store.get().maxDistance, "copy-once：已存在文件不得被默认资源覆盖");
        assertEquals(true, store.get().autoUse, "缺键仍回落模型默认");
    }

    @Test
    void reloadReadsDiskNotResource() throws IOException {
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class,
                tempDir,
                "config.yml",
                "defaults/nested-sample.yml",
                getClass().getClassLoader());
        Files.writeString(tempDir.resolve("config.yml"), "max-distance: 9\n", StandardCharsets.UTF_8);
        store.reload();
        assertEquals(9, store.get().maxDistance);
    }

    @Test
    void corruptFileFallsBackToBundledResourceNotSynthesizedText() throws IOException {
        Files.writeString(tempDir.resolve("config.yml"), "max-distance: 1\nmax-distance: 2\n", StandardCharsets.UTF_8);
        ConfigFileStore<Sample> store = ConfigFileStore.load(
                Sample.class,
                tempDir,
                "config.yml",
                "defaults/nested-sample.yml",
                getClass().getClassLoader());
        assertTrue(
                store.lastIssues().stream().anyMatch(i -> i.level() == IssueLevel.ERROR),
                "损坏文件须报 ERROR，实际：" + store.lastIssues());
        assertEquals(256, store.get().maxDistance, "回落应取自带资源里的默认值");
        assertTrue(store.text().contains("# 嵌套子目录默认资源"), "回落文档应逐字取用出厂资源，而非合成文本");
    }
}
