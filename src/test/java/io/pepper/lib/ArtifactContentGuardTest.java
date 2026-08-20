package io.pepper.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * 普通库产物守卫（双模式重构文档 §9.1）：
 * pepper-lib JAR 是可编译/shade 输入，不是可加载插件——
 * 不得包含 paper-plugin.yml 与 PepperLibPlugin 主类；
 * 必须包含全部公开 io.pepper.lib.* 类（含运行时服务接口）。
 */
class ArtifactContentGuardTest {

    private static final Path JAR = Path.of("build/libs/pepper-lib-0.6.0.jar");

    private static Set<String> entries() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void libraryJarIsNotALoadablePlugin() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew jar first (test task depends on it)");
        final Set<String> entries = entries();
        assertFalse(entries.contains("paper-plugin.yml"), "library jar must not carry plugin descriptor");
        assertFalse(
                entries.stream().anyMatch(e -> e.contains("io/pepper/lib/plugin/PepperLibPlugin")),
                "library jar must not carry the front-end plugin main class");
    }

    @Test
    void libraryJarCarriesAllPublicApiClasses() throws IOException {
        final Set<String> entries = entries();
        for (final String required : new String[] {
            "io/pepper/lib/runtime/PepperLibRuntime.class",
            "io/pepper/lib/task/PepperScheduler.class",
            "io/pepper/lib/task/ThreadGuard$Instance.class",
            "io/pepper/lib/storage/MigrationRunner.class",
            "io/pepper/lib/gui/GuiHost.class",
            "io/pepper/lib/i18n/LanguageBundle.class",
            "io/pepper/lib/confirm/ConfirmRegistry.class",
            "io/pepper/lib/money/Amounts.class",
            "io/pepper/lib/validation/Preconditions.class",
        }) {
            assertTrue(entries.contains(required), "library jar must contain " + required);
        }
    }

    @Test
    void libraryJarTargetsJava17Bytecode() throws IOException {
        // Java 17 字节码基线（2025-08 决策）：解锁纯 Java 模块（storage/money/validation）
        // 在 Java 17 运行时（如 PepperBotBindManager 的 Spigot 生态）的坐标/shade 消费。
        // 任何类文件主版本超过 61（Java 17）即失败。
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew jar first (test task depends on it)");
        final Set<String> classEntries =
                entries().stream().filter(e -> e.endsWith(".class")).collect(Collectors.toSet());
        assertFalse(classEntries.isEmpty(), "jar must contain class files");
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            for (final String name : classEntries) {
                final byte[] header;
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                    header = in.readNBytes(8);
                }
                assertEquals(0xCAFEBABE, ByteBuffer.wrap(header).getInt(), name + " invalid class file header");
                final int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                assertEquals(61, major, name + " must target Java 17 bytecode (major 61)");
            }
        }
    }
}
