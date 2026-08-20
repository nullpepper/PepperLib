package io.pepper.lib.plugin;

import io.pepper.lib.runtime.ServerVersions;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务器版本 → 能力集的纯函数决策（注解驱动能力决策）。
 *
 * <p>注册表（capability → 最低版本）来自 {@link CapabilityAnnotationScanner} 对
 * {@code @MinMinecraftVersion} 注解的扫描；本类只做比较决策，不依赖 Bukkit，
 * 可纯 JUnit 测试。{@link PepperLibPlugin} 负责取版本字符串与注册表并调用。</p>
 */
final class CapabilityResolver {

    private CapabilityResolver() {}

    /**
     * 按 {@code Bukkit.getMinecraftVersion()} 返回值 × 能力注册表决策能力集。
     *
     * @param minecraftVersion 服务器 MC 版本（如 {@code "1.18.2"} / {@code "26.1.2"}）
     * @param capabilityMinVersions capability → 最低版本注册表（不可变视图）
     * @return 能力集（不可变）；版本低于某能力门槛的能力不包含在内
     */
    static Set<String> resolve(final String minecraftVersion, final Map<String, int[]> capabilityMinVersions) {
        final int[] version = ServerVersions.parse(minecraftVersion);
        final Set<String> capabilities = new HashSet<>();
        for (final Map.Entry<String, int[]> entry : capabilityMinVersions.entrySet()) {
            if (ServerVersions.isAtLeast(version, entry.getValue())) {
                capabilities.add(entry.getKey());
            }
        }
        return Set.copyOf(capabilities);
    }
}
