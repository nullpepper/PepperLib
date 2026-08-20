package io.pepper.lib.i18n;

import java.util.Locale;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家 → 语言环境 解析（策略留插件：Claim 注入 override + 客户端 locale，Union 不注入）。
 */
@FunctionalInterface
public interface LocaleResolver {

    /**
     * 返回玩家使用的 {@link Locale}。
     *
     * @param player 目标玩家；可能为 null（控制台/无玩家上下文）
     * @param defaultLocale 语言包默认语言环境
     * @return 玩家语言环境
     */
    Locale resolve(@Nullable Player player, Locale defaultLocale);
}
