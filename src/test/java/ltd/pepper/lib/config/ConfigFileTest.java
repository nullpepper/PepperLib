package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 设计文档 §7 L1 生命周期：copy-once 落盘（注释安全）、readUtf8（BOM 剥离）、原子写。 */
class ConfigFileTest {

    @TempDir
    Path dir;

    private static final String RESOURCE = "ltd/pepper/lib/config/sample-default.yml";

    @Test
    void copyDefaultIfMissingCopiesOnceAndNeverOverwrites() throws IOException {
        Path target = dir.resolve(RESOURCE);
        ClassLoader loader = ConfigFileTest.class.getClassLoader();
        assertTrue(ConfigFile.copyDefaultIfMissing(dir, RESOURCE, loader), "首次应复制");
        assertTrue(Files.exists(target));
        String first = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(first.contains("# 默认键注释"), "默认文件应含注释");
        assertTrue(first.contains("requireAxe: true"));

        // 已存在 → 不覆盖（即使磁盘已有管理员修改）
        Files.writeString(target, "admin-changed: true\n", StandardCharsets.UTF_8);
        assertFalse(ConfigFile.copyDefaultIfMissing(dir, RESOURCE, loader), "已存在不应再复制");
        assertEquals("admin-changed: true\n", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void copyDefaultIfMissingThrowsOnMissingResource() {
        assertThrows(
                IOException.class,
                () -> ConfigFile.copyDefaultIfMissing(
                        dir, "no/such.yml", getClass().getClassLoader()));
    }

    @Test
    void readUtf8StripsBom() throws IOException {
        Path f = dir.resolve("bom.yml");
        Files.write(f, ("\uFEFFa: 1\n").getBytes(StandardCharsets.UTF_8));
        String s = ConfigFile.readUtf8(f);
        assertEquals("a: 1\n", s);
        assertFalse(s.startsWith("\uFEFF"));
        assertThrows(IOException.class, () -> ConfigFile.readUtf8(dir.resolve("missing.yml")));
    }

    @Test
    void writeAtomicReplacesAndCreatesParentDirs() throws IOException {
        Path f = dir.resolve("sub").resolve("config.yml");
        ConfigFile.writeAtomic(f, "a: 1\n");
        assertEquals("a: 1\n", Files.readString(f, StandardCharsets.UTF_8));

        ConfigFile.writeAtomic(f, "a: 2\n");
        assertEquals("a: 2\n", Files.readString(f, StandardCharsets.UTF_8));
        // 无残留临时文件
        try (var stream = Files.list(dir.resolve("sub"))) {
            assertEquals(1, stream.count());
        }
    }
}
