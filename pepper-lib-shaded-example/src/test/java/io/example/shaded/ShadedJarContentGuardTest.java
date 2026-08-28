package io.example.shaded;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * shade 模式产物守卫（双模式重构文档 §9.4）：
 * 消费者 JAR 必须包含 relocate 后的 PepperLib 类；不得包含原始 ltd.pepper.lib.*、
 * paper-plugin.yml 与 PepperLibPlugin 主类。
 */
class ShadedJarContentGuardTest {

    private static final Path JAR = Path.of("build/libs/ShadedExample-1.0.0.jar");

    private static Set<String> entries() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void shadedJarCarriesRelocatedLibraryClasses() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew shadowJar first (test task depends on it)");
        final Set<String> entries = entries();
        assertTrue(entries.contains("io/example/shaded/lib/task/PepperScheduler.class"));
        assertTrue(entries.contains("io/example/shaded/lib/runtime/PepperLibRuntime.class"));
        assertTrue(entries.contains("io/example/shaded/ShadedExamplePlugin.class"));
    }

    @Test
    void shadedJarLeaksNoOriginalLibraryOrPluginDescriptor() throws IOException {
        final Set<String> entries = entries();
        assertFalse(
                entries.stream().anyMatch(e -> e.startsWith("io/pepper/lib/")),
                "shaded jar must not contain original ltd.pepper.lib.* classes");
        assertFalse(entries.contains("paper-plugin.yml"), "shaded jar must not carry the front-end plugin descriptor");
        assertFalse(
                entries.stream().anyMatch(e -> e.contains("PepperLibPlugin")),
                "shaded jar must not carry the front-end plugin main class");
    }
}
