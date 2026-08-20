package io.pepper.lib.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Vault 软依赖解析（内部设计文档 extraction-audit C 项）。
 *
 * <p>惰性解析经济服务（重载安全），未安装/未注册时返回 null。
 * 桥本体（接口契约）由插件各自定义，本类只提供解析机制。</p>
 *
 * <p><b>LinkageError 兜底</b>：方法体引用 Vault 类型，Vault 完全未安装时
 * 调用即 NoClassDefFoundError——按软依赖语义返回 null（调用方降级），
 * 不向插件启动路径传播。</p>
 */
public final class VaultSupport {

    private VaultSupport() {}

    /**
     * 解析已注册的 Vault 经济服务提供者。
     *
     * @return 经济服务；Vault 未安装 / 未注册服务时返回 null
     */
    public static @Nullable Economy economy() {
        try {
            final RegisteredServiceProvider<Economy> provider =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            return provider == null ? null : provider.getProvider();
        } catch (final LinkageError e) {
            // Vault 未安装：软依赖降级，不中断调用方。
            return null;
        }
    }
}
