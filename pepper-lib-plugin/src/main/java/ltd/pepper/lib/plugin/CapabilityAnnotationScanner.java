package ltd.pepper.lib.plugin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import ltd.pepper.lib.runtime.MinMinecraftVersion;
import ltd.pepper.lib.runtime.ServerVersions;

/**
 * {@code @MinMinecraftVersion} 注解扫描器（注解驱动能力决策）。
 *
 * <p>定位 {@code MinMinecraftVersion} 类所在 CodeSource（前置插件 jar 或测试
 * classes 目录），遍历其中 {@code io/pepper/lib} 前缀的全部类，读取类级注解，
 * 聚合为 {@code capability → 最低版本} 注册表。同能力多类冲突时取最严值
 * （版本较高者）；类加载失败（如低版本服务器缺可选依赖）时跳过该类的注解，
 * 绝不让前置插件启动崩溃。</p>
 *
 * <p>零第三方依赖；反射读取注解不执行方法、不触发静态初始化，低版本服务器安全。</p>
 */
final class CapabilityAnnotationScanner {

    /** 扫描的包前缀（资源路径形态）。 */
    private static final String PACKAGE_PREFIX = "io/pepper/lib";

    /** 定位用标记类：与其同一 CodeSource 的所有库类一并扫描。 */
    private static final String MARKER_CLASS = "ltd.pepper.lib.runtime.MinMinecraftVersion";

    private CapabilityAnnotationScanner() {}

    /**
     * 扫描 {@code loader} 可加载的库类注解。
     *
     * @param loader 类加载器（前置插件加载器；其加载的 {@code MinMinecraftVersion}
     *     所在位置决定扫描范围）
     * @return 不可变注册表；库类不可用/无注解时为 {@code Map.of()}
     */
    static Map<String, int[]> scan(final ClassLoader loader) {
        final Map<String, int[]> registry = new HashMap<>();
        try {
            final Class<?> marker = loader.loadClass(MARKER_CLASS);
            final URL location = marker.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return Map.of();
            }
            if (isJarLocation(location)) {
                scanJar(location, loader, registry);
            } else {
                scanDirectory(Path.of(location.toURI()), loader, registry);
            }
        } catch (final LinkageError | ClassNotFoundException | URISyntaxException | IOException e) {
            // 库类不可用时视为无能力：前置插件仍须可启动（可选能力语义）。
            return Map.of();
        }
        return Map.copyOf(registry);
    }

    private static void scanJar(final URL location, final ClassLoader loader, final Map<String, int[]> registry)
            throws IOException {
        try (JarFile jar = openJar(location)) {
            final var entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (isClassUnderPackage(name)) {
                    register(classNameFromPath(name), loader, registry);
                }
            }
        }
    }

    /** 两种 jar 形态：{@code jar:file:...!/}（协议 jar）与 {@code file:...jar}（裸文件）。 */
    private static JarFile openJar(final URL location) throws IOException {
        if ("jar".equals(location.getProtocol())) {
            return ((java.net.JarURLConnection) location.openConnection()).getJarFile();
        }
        try {
            return new JarFile(new java.io.File(location.toURI()));
        } catch (final URISyntaxException e) {
            throw new IOException("invalid jar location: " + location, e);
        }
    }

    private static boolean isJarLocation(final URL location) {
        return "jar".equals(location.getProtocol()) || location.getPath().endsWith(".jar");
    }

    private static void scanDirectory(final Path root, final ClassLoader loader, final Map<String, int[]> registry)
            throws IOException {
        final Path packageDir = root.resolve(PACKAGE_PREFIX);
        if (!Files.isDirectory(packageDir)) {
            return;
        }
        try (var files = Files.walk(packageDir)) {
            files.filter(path -> path.toString().endsWith(".class"))
                    .forEach(path ->
                            register(classNameFromPath(root.relativize(path).toString()), loader, registry));
        }
    }

    private static void register(final String className, final ClassLoader loader, final Map<String, int[]> registry) {
        try {
            final Class<?> type = Class.forName(className, false, loader);
            final MinMinecraftVersion annotation = type.getAnnotation(MinMinecraftVersion.class);
            if (annotation != null) {
                merge(registry, annotation.capability(), ServerVersions.parse(annotation.value()));
            }
        } catch (final LinkageError | ClassNotFoundException e) {
            // 类加载失败（可选依赖缺失等）：跳过该类的注解，不影响其他能力。
        }
    }

    private static void merge(final Map<String, int[]> registry, final String capability, final int[] minVersion) {
        registry.merge(capability, minVersion, (a, b) -> ServerVersions.isAtLeast(a, b) ? a : b);
    }

    private static boolean isClassUnderPackage(final String resourcePath) {
        if (!resourcePath.startsWith(PACKAGE_PREFIX + "/") || !resourcePath.endsWith(".class")) {
            return false;
        }
        // 跳过包信息/模块信息与内部类：它们不承载能力声明。
        final String base =
                resourcePath.substring(PACKAGE_PREFIX.length() + 1, resourcePath.length() - ".class".length());
        return !base.contains("package-info") && !base.contains("module-info") && !base.contains("$");
    }

    private static String classNameFromPath(final String resourcePath) {
        return resourcePath
                .substring(0, resourcePath.length() - ".class".length())
                .replace('/', '.');
    }
}
