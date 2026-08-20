package io.pepper.lib.i18n;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 外部占位符解析钩子（模板解析前字符串级执行）。
 *
 * <p>典型实现为 PlaceholderAPI 软依赖解析（{@link PapiPlaceholderResolver}）；未安装、
 * 未启用或解析失败时必须原样返回输入，绝不中断消息渲染。</p>
 */
@FunctionalInterface
public interface PlaceholderResolver {

    /**
     * 解析文本中的外部占位符。
     *
     * @param player 目标玩家；可能为 null
     * @param text 原始模板文本
     * @return 解析后的文本；无法解析时原样返回
     */
    String resolve(@Nullable Player player, String text);
}
