package io.pepper.lib.i18n;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI 软依赖解析器（设计文档 §7）。
 *
 * <p>守卫模式：player 为空 / 文本不含 {@code %} / PAPI 插件未安装或未启用时原样返回，
 * 全程不触碰 PAPI 类（软依赖运行时安全）；解析异常兜底（含 {@link LinkageError}，
 * 如 PAPI 存在但类损坏），不中断消息渲染。</p>
 */
public final class PapiPlaceholderResolver implements PlaceholderResolver {

    /** 单例：解析器无状态。 */
    public static final PapiPlaceholderResolver INSTANCE = new PapiPlaceholderResolver();

    private PapiPlaceholderResolver() {}

    @Override
    public String resolve(@Nullable final Player player, final String text) {
        if (player == null || text == null || !text.contains("%")) {
            return text;
        }
        final Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return text;
        }
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (final RuntimeException | LinkageError e) {
            // PAPI 异常（含 Error 子类）不能中断原消息渲染。
            return text;
        }
    }
}
