package io.pepper.lib.papi;

import java.util.function.Supplier;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * PAPI 扩展注册样板（内部设计文档 extraction-audit D 项）。
 *
 * <p>软依赖守卫：PAPI 未安装/未启用时返回 null 且<b>不调用工厂</b>——扩展类
 * extends PAPI 类型，无 PAPI 时构造即 NoClassDefFoundError，因此实例创建必须
 * 延迟到守卫之后。注册异常兜底（含 {@link LinkageError}：PAPI 存在但类损坏/
 * 版本错配时构造同样抛 Error 子类），不中断插件启动；返回注册成功的实例供
 * 调用方保留（onDisable 注销等）。扩展元数据与 onRequest 仍由插件实现。</p>
 */
public final class PapiExpansionSupport {

    private PapiExpansionSupport() {}

    /**
     * 注册 PlaceholderAPI 扩展。
     *
     * @param factory 扩展工厂（仅在 PAPI 已安装且启用时调用）
     * @return 注册成功的扩展实例；PAPI 未安装/未启用或注册失败时为 null
     * @param <T> 扩展类型
     */
    public static <T extends PlaceholderExpansion> @Nullable T register(final Supplier<T> factory) {
        final Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return null;
        }
        try {
            final T expansion = factory.get();
            return expansion.register() ? expansion : null;
        } catch (final RuntimeException | LinkageError e) {
            // 软依赖兜底：吞掉但留痕，注册失败可诊断。
            Bukkit.getLogger().warning("Failed to register PAPI expansion: " + e);
            return null;
        }
    }
}
