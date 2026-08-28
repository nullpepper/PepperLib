package io.pepper.lib;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 构建基础设施守卫（2026-08 引入）：
 *
 * <ul>
 *   <li>japicmp 二进制兼容门必须接入 {@code check}（否则“守护”只是摆设，删除/签名变更
 *       不会被绿门拦截）；</li>
 *   <li>依赖/插件版本必须在 {@code gradle/libs.versions.toml} 单一来源——三个
 *       {@code build.gradle.kts} 不得散落硬编码版本（升级只改目录一处）。</li>
 * </ul>
 */
class BuildInfraGuardTest {

    private static final List<String> HARDCODED_VERSIONS = List.of(
            "26.1.2.build.74-stable", // paper-api
            "5.11.4", // junit-bom
            "8.9.0", // spotless 插件
            "9.2.2", // shadow 插件
            "0.4.5", // japicmp 插件
            "2.97.0", // palantirJavaFormat
            "4.115.0", // mockbukkit
            "5.23.0", // mockito-core
            "3.46.1.0", // sqlite-jdbc
            "2.11.6", // placeholderapi
            "26.0.1", // jetbrains annotations
            "3.0.0" // aswm-api
            // flow-nbt 1.0.0 未列入：与 shaded-example 自身项目版本号（"1.0.0"）冲突；
            // 其版本已入版本目录，靠 BUILD_FILES 扫描防未来漂移。
            );

    private static final List<String> BUILD_FILES = List.of(
            "build.gradle.kts",
            "pepper-lib-plugin/build.gradle.kts",
            "pepper-lib-shaded-example/build.gradle.kts",
            "pepper-lib-aswm-provider/build.gradle.kts");

    @Test
    void checkWiresJapicmp() {
        final String rootBuild = read("build.gradle.kts");
        assertTrue(
                rootBuild.contains("tasks.register<me.champeau.gradle.japicmp.JapicmpTask>(\"japicmp\")"),
                "japicmp 任务必须注册");
        assertTrue(rootBuild.contains("dependsOn(tasks.named(\"japicmp\"))"), "check 必须依赖 japicmp（二进制兼容门进绿门）");
    }

    @Test
    void versionCatalogExists() {
        assertTrue(Files.isRegularFile(Path.of("gradle", "libs.versions.toml")), "版本目录 gradle/libs.versions.toml 必须存在");
    }

    @Test
    void noHardcodedVersionsOutsideCatalog() {
        for (final String buildFile : BUILD_FILES) {
            final String content = read(buildFile);
            for (final String version : HARDCODED_VERSIONS) {
                assertFalse(
                        content.contains(version),
                        buildFile + " 不得硬编码版本 " + version + "（应引用 gradle/libs.versions.toml）");
            }
        }
    }

    private static String read(final String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
