package ltd.pepper.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * 普通库产物守卫（双模式重构文档 §9.1）：
 * pepper-lib JAR 是可编译/shade 输入，不是可加载插件——
 * 不得包含 plugin.yml / paper-plugin.yml 与 PepperLibPlugin 主类；
 * 必须包含全部公开 ltd.pepper.lib.* 类（含运行时服务接口）。
 *
 * <p><b>2026-09 修复</b>：本测试此前把产物路径硬编码为 {@code pepper-lib-0.8.0.jar}，并断言
 * 包名统一（commit db4478c）之前的 {@code io/pepper/lib/...} 条目——只有该旧 jar 恰好残留在
 * {@code build/libs} 时才通过，{@code clean build} 必然失败（3/3 用例）。现在产物按当前版本
 * 定位、条目统一为 {@code ltd/pepper/lib/...}，并新增「由源码目录推导必需条目」的断言，
 * 使守卫真正校验本轮产物。</p>
 */
class ArtifactContentGuardTest {

    /** 当前版本（由 build.gradle.kts 的 test 任务注入）；缺失即失败，不静默退化。 */
    private static final String VERSION = Objects.requireNonNull(
            System.getProperty("pepperLibVersion"),
            "缺少 pepperLibVersion 系统属性（应由根 build.gradle.kts 的 test 任务注入 project.version）");

    private static final Path JAR = Path.of("build/libs/pepper-lib-" + VERSION + ".jar");

    private static final List<String> REQUIRED_API_CLASSES = List.of(
            "ltd/pepper/lib/runtime/PepperLibRuntime.class",
            "ltd/pepper/lib/task/PepperScheduler.class",
            "ltd/pepper/lib/task/ThreadGuard$Instance.class",
            "ltd/pepper/lib/storage/MigrationRunner.class",
            "ltd/pepper/lib/gui/GuiHost.class",
            "ltd/pepper/lib/dialog/DialogHost.class",
            "ltd/pepper/lib/dialog/MultiActionDialog.class",
            "ltd/pepper/lib/i18n/LanguageBundle.class",
            "ltd/pepper/lib/confirm/ConfirmRegistry.class",
            "ltd/pepper/lib/money/Amounts.class",
            "ltd/pepper/lib/validation/Preconditions.class",
            "ltd/pepper/lib/world/InstanceWorldService.class",
            "ltd/pepper/lib/world/WorldProviderError.class",
            "ltd/pepper/lib/persist/PersistentStore.class",
            "ltd/pepper/lib/persist/StoreCodec.class");

    private static Set<String> entries() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "产物不存在：" + JAR + "（test 任务依赖 jar；请先构建）");
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void libraryJarIsNotALoadablePlugin() throws IOException {
        final Set<String> entries = entries();
        assertFalse(entries.contains("plugin.yml"), "普通库 jar 不得携带插件描述符");
        assertFalse(entries.contains("paper-plugin.yml"), "library jar must not carry plugin descriptor");
        assertFalse(
                entries.stream().anyMatch(e -> e.contains("ltd/pepper/lib/plugin/PepperLibPlugin")),
                "library jar must not carry the front-end plugin main class");
    }

    @Test
    void libraryJarCarriesAllPublicApiClasses() throws IOException {
        final Set<String> entries = entries();
        for (final String required : REQUIRED_API_CLASSES) {
            assertTrue(entries.contains(required), "library jar must contain " + required);
        }
    }

    @Test
    void libraryJarCarriesEveryDialogSourceClass() throws IOException {
        // 由源码目录推导必需条目（而非硬编码类名字符串）：src/main 里每个 dialog 顶层类都必须
        // 出现在本轮产物中，漏打包或改名漂移都无法蒙混过关。
        final Set<String> entries = entries();
        final Path dialogSources = Path.of("src", "main", "java", "ltd", "pepper", "lib", "dialog");
        assertTrue(Files.isDirectory(dialogSources), "dialog 源码目录必须存在：" + dialogSources);
        try (Stream<Path> files = Files.list(dialogSources)) {
            final List<Path> sources =
                    files.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty(), "dialog 源码目录不得为空");
            for (final Path source : sources) {
                final String name = source.getFileName().toString();
                if ("package-info.java".equals(name)) {
                    // 无注解的 package-info 不产生 class 文件（javac 只为带注解的包声明生成）。
                    continue;
                }
                final String entry = "ltd/pepper/lib/dialog/" + name.replace(".java", ".class");
                assertTrue(entries.contains(entry), "library jar must contain " + entry);
            }
        }
    }

    @Test
    void libraryJarTargetsJava17Bytecode() throws IOException {
        // Java 17 字节码基线（2025-08 决策）：解锁纯 Java 模块（storage/money/validation）
        // 在 Java 17 运行时（如 PepperBotBindManager 的 Spigot 生态）的坐标/shade 消费。
        // 任何类文件主版本超过 61（Java 17）即失败。
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
