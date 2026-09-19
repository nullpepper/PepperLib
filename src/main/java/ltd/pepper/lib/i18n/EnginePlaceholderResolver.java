package ltd.pepper.lib.i18n;

import ltd.pepper.lib.placeholder.PlaceholderSupport;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * PepperPlaceholder 引擎解析器：模板字符串级的 {@code ${identifier_params}} 解析。
 *
 * <p>装配到 {@link LanguageBundle#setPlaceholderResolver(PlaceholderResolver)} 后，
 * 每次面向玩家的渲染会先用引擎解析模板中的<b>注册表占位符</b>，再交给 i18n 层做
 * <b>调用期参数</b>替换（{@code ${key}} ← 渲染时传入的 map）。两层共用同一套
 * {@code ${}} 定界符，靠「引擎解析不了的标识符原样保留」这条契约共存：
 * 未注册的 {@code ${name}} 会原封不动地穿到 i18n 层。</p>
 *
 * <p><b>无玩家上下文时整体跳过</b>（与 {@link PapiPlaceholderResolver} 同一守卫）：
 * 引擎对 {@code null} 玩家会把 {@code ${player}} 这类占位符解析成<b>空串</b>
 * （PAPI 的 player 扩展对离线/空玩家返回 {@code ""}），而 {@code ${player}} 在本仓库
 * 的语言模板里是<b>调用期参数</b>（PepperUnion 有 22 处）—— 一旦被引擎吃掉，
 * i18n 层就再也替换不到，控制台/RCON 发送者会看到缺参数的文案。
 * 实测：{@code A${player}B} 在无玩家上下文下会变成 {@code AB}。</p>
 *
 * <p>软依赖守卫：引擎宿主缺失、文本不含 {@code ${}} 或解析异常时一律原样返回，
 * 绝不中断消息渲染。</p>
 */
public final class EnginePlaceholderResolver implements PlaceholderResolver {

    /** 单例：解析器无状态。 */
    public static final EnginePlaceholderResolver INSTANCE = new EnginePlaceholderResolver();

    private EnginePlaceholderResolver() {}

    @Override
    public String resolve(final @Nullable Player player, final String text) {
        if (player == null) {
            // 见类注释：无玩家上下文时引擎会把 ${player} 之类解析成空串，
            // 与 i18n 层的调用期参数撞名，必须整体跳过。
            return text;
        }
        return PlaceholderSupport.resolve(player, text);
    }
}
