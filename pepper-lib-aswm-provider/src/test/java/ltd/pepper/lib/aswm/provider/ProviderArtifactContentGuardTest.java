package ltd.pepper.lib.aswm.provider;

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
 * provider 薄 jar 产物守卫：可加载插件（plugin.yml + 主类）但<b>不打包</b>
 * PepperLib 类（运行时由前置插件提供，同 ClassLoader 契约）与 ASWM API 类
 * （运行时由 ASP 环境提供）——薄 jar 是跨插件服务互通的前提。
 */
class ProviderArtifactContentGuardTest {

    private static final Path JAR = Path.of("build/libs/pepper-lib-aswm-provider-0.8.0.jar");

    private static Set<String> entries() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            return zip.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
    }

    @Test
    void providerJarIsALoadablePlugin() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew jar first (test task depends on it)");
        final Set<String> entries = entries();
        assertTrue(entries.contains("plugin.yml"), "provider jar must carry plugin.yml");
        assertTrue(entries.contains("io/pepper/lib/aswm/provider/PepperLibAswmProviderPlugin.class"));
    }

    @Test
    void providerJarIsThin() throws IOException {
        assertTrue(Files.isRegularFile(JAR), "run ./gradlew jar first (test task depends on it)");
        final Set<String> entries = entries();
        assertFalse(
                entries.stream()
                        .filter(e -> e.endsWith(".class"))
                        .anyMatch(e -> e.startsWith("io/pepper/lib/") && !e.startsWith("io/pepper/lib/aswm/")),
                "provider jar must not bundle PepperLib core classes (provided by PepperLib front-end plugin)");
        assertFalse(
                entries.stream()
                        .filter(e -> e.endsWith(".class"))
                        .anyMatch(e -> e.startsWith("com/infernalsuite/") || e.startsWith("com/flowpowered/")),
                "provider jar must not bundle ASP API or flow-nbt classes (provided by ASP environment)");
        assertFalse(entries.contains("paper-plugin.yml"), "provider jar must use plugin.yml (wide api-version range)");
    }
}
