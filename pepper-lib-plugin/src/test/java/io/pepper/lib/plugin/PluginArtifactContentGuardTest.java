package io.pepper.lib.plugin;

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
 * 前置插件产物守卫（双模式重构文档 §9.2）：
 * PepperLib.jar 是服务器可加载插件——必须含 plugin.yml 与主类；
 * 必须包含未 relocate 的 io.pepper.lib.*（前置模式契约核心）；
 * 不得携带任何消费者私有命名空间类。
 */
class PluginArtifactContentGuardTest {

    private static final Path JAR = Path.of("build/libs/PepperLib-0.4.0.jar");

    private static Set<String> entries() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void pluginJarIsALoadableFrontEndPlugin() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew shadowJar first (test task depends on it)");
        final Set<String> entries = entries();
        // 自适应加载（2025-08 决策）：只用 plugin.yml（api-version '1.18'）——
        // paper-plugin.yml 在 Paper 26.x 有 api-version 下限校验（1.18 too old），
        // 而 plugin.yml 的 '1.18' 在 1.18.2 ~ 26.x 全区间被接受。
        assertTrue(entries.contains("plugin.yml"), "plugin jar must carry plugin.yml");
        assertFalse(entries.contains("paper-plugin.yml"), "plugin jar must not carry paper-plugin.yml");
        assertTrue(entries.contains("io/pepper/lib/plugin/PepperLibPlugin.class"));
        assertTrue(entries.contains("io/pepper/lib/runtime/PepperLibRuntime.class"));
    }

    @Test
    void pluginJarCarriesUnrelocatedLibraryClassesOnly() throws IOException {
        final Set<String> entries = entries();
        // 前置模式核心约束：未 relocate 的 io.pepper.lib.* 全部在场。
        assertTrue(entries.contains("io/pepper/lib/task/ThreadGuard$Instance.class"));
        assertTrue(entries.contains("io/pepper/lib/i18n/LanguageBundle.class"));
        assertTrue(entries.contains("io/pepper/lib/gui/GuiHost.class"));
        // 不得混入消费者/其他命名空间类（relocate 泄漏检测）。
        assertFalse(
                entries.stream().anyMatch(e -> e.startsWith("io/pepper/claim/") || e.startsWith("io/pepper/union/")),
                "plugin jar must not contain consumer classes");
    }
}
