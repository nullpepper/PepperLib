package io.pepper.lib;

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
 * 普通库产物守卫（双模式重构文档 §9.1）：
 * pepper-lib JAR 是可编译/shade 输入，不是可加载插件——
 * 不得包含 paper-plugin.yml 与 PepperLibPlugin 主类；
 * 必须包含全部公开 io.pepper.lib.* 类（含运行时服务接口）。
 */
class ArtifactContentGuardTest {

    private static final Path JAR = Path.of("build/libs/pepper-lib-0.2.0.jar");

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
}
