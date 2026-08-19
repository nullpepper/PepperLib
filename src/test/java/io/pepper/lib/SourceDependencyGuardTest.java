package io.pepper.lib;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 依赖方向守卫（阶段 7）：lib 源码零插件引用。
 *
 * <p>PepperLib 是共享库，不得反向依赖任何插件包（{@code io.pepper.claim} /
 * {@code io.pepper.union}）；否则插件升级 lib 会形成循环依赖，且 lib 失去
 * 独立发布能力。本测试扫描 {@code src/main} 全部 Java 源码做字符串断言。</p>
 */
class SourceDependencyGuardTest {

    private static final List<String> FORBIDDEN = List.of("io.pepper.claim", "io.pepper.union");

    @Test
    void mainSourcesNeverReferencePluginPackages() throws IOException {
        final Path root = Path.of("src/main");
        assertTrue(Files.isDirectory(root), "test must run from the PepperLib repo root");
        try (Stream<Path> files = Files.walk(root)) {
            final List<String> violations = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream()
                                    .filter(line -> FORBIDDEN.stream().anyMatch(line::contains))
                                    .map(line -> path + ": " + line.trim());
                        } catch (final IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertTrue(
                    violations.isEmpty(),
                    "PepperLib main sources must not reference plugin packages:\n" + String.join("\n", violations));
        }
    }
}
