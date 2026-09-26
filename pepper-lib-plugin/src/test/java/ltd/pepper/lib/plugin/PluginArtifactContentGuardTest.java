package ltd.pepper.lib.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * 前置插件产物守卫（双模式重构文档 §9.2）：
 * PepperLib.jar 是服务器可加载插件——必须含 plugin.yml 与主类；
 * 必须包含未 relocate 的 ltd.pepper.lib.*（前置模式契约核心）；
 * 不得携带任何消费者私有命名空间类。
 *
 * <p><b>2026-09 修复</b>：本测试此前把产物路径硬编码为 {@code PepperLib-0.8.0.jar}，并断言
 * 包名统一（commit db4478c）之前的 {@code io/pepper/lib/...} 条目——只有该旧 jar 恰好残留在
 * {@code build/libs} 时才通过，{@code clean build} 必然失败。现在产物按当前版本定位、
 * 条目统一为 {@code ltd/pepper/lib/...}，并纳入 dialog 包的实际产物类名，使守卫真正校验
 * 本轮产物。</p>
 */
class PluginArtifactContentGuardTest {

    /** 当前版本（由 build.gradle.kts 的 test 任务注入）；缺失即失败，不静默退化。 */
    private static final String VERSION = Objects.requireNonNull(
            System.getProperty("pepperLibVersion"),
            "缺少 pepperLibVersion 系统属性（应由 pepper-lib-plugin/build.gradle.kts 的 test 任务注入 project.version）");

    private static final Path JAR = Path.of("build/libs/PepperLib-" + VERSION + ".jar");

    /** 未 relocate 的库类必须整体在场（断言的是产物里的真实条目名，拼错即失败）。 */
    private static final List<String> REQUIRED_UNRELOCATED_CLASSES = List.of(
            "ltd/pepper/lib/task/ThreadGuard$Instance.class",
            "ltd/pepper/lib/i18n/LanguageBundle.class",
            "ltd/pepper/lib/gui/GuiHost.class",
            "ltd/pepper/lib/dialog/DialogHost.class",
            "ltd/pepper/lib/dialog/DialogClick.class",
            "ltd/pepper/lib/dialog/DialogButton.class",
            "ltd/pepper/lib/dialog/MultiActionDialog.class",
            "ltd/pepper/lib/dialog/MultiActionDialog$Builder.class",
            "ltd/pepper/lib/dialog/MultiActionDialog$ExitButton.class",
            "ltd/pepper/lib/dialog/PaperDialogs.class");

    private static Set<String> entries() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "产物不存在：" + JAR + "（test 任务依赖 shadowJar；请先构建）");
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void pluginJarIsALoadableFrontEndPlugin() throws IOException {
        final Set<String> entries = entries();
        // 自适应加载（2025-08 决策）：只用 plugin.yml（api-version '1.18'）——
        // paper-plugin.yml 在 Paper 26.x 有 api-version 下限校验（1.18 too old），
        // 而 plugin.yml 的 '1.18' 在 1.18.2 ~ 26.x 全区间被接受。
        assertTrue(entries.contains("plugin.yml"), "plugin jar must carry plugin.yml");
        assertFalse(entries.contains("paper-plugin.yml"), "plugin jar must not carry paper-plugin.yml");
        assertTrue(entries.contains("ltd/pepper/lib/plugin/PepperLibPlugin.class"));
        assertTrue(entries.contains("ltd/pepper/lib/runtime/PepperLibRuntime.class"));
    }

    @Test
    void pluginJarCarriesUnrelocatedLibraryClassesOnly() throws IOException {
        final Set<String> entries = entries();
        // 前置模式核心约束：未 relocate 的 ltd.pepper.lib.* 全部在场（含新增 dialog 包）。
        for (final String required : REQUIRED_UNRELOCATED_CLASSES) {
            assertTrue(entries.contains(required), "plugin jar must contain " + required);
        }
        // 不得混入消费者/其他命名空间类（relocate 泄漏检测）。
        assertFalse(
                entries.stream().anyMatch(e -> e.startsWith("ltd/pepper/claim/") || e.startsWith("ltd/pepper/union/")),
                "plugin jar must not contain consumer classes");
    }
}
