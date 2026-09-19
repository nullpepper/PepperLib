package ltd.pepper.lib.placeholder;

import java.util.Set;
import java.util.function.BiFunction;
import ltd.pepper.placeholder.PlaceholderEngine;
import ltd.pepper.placeholder.PlaceholderEngine.Ctx;
import ltd.pepper.placeholder.PlaceholderEngine.Registry;
import ltd.pepper.placeholder.PlaceholderService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.Nullable;

/**
 * PepperPlaceholder 原生占位符支持（Pepper 系列插件对外占位符的唯一注册入口）。
 *
 * <p><b>为什么是门面而不是直接调引擎</b>：消费方插件的 handler 是 lambda，
 * 其目标类型若直接是引擎的 {@code SyncFn}，则 PepperPlaceholder 未安装时
 * <b>lambda 创建本身就</b>抛 {@code NoClassDefFoundError} —— 软依赖名不副实。
 * 本门面只暴露 JDK 类型（{@link BiFunction}），引擎类型的解析全部推迟到
 * 「宿主插件在场」之后，因此 {@code softdepend} 语义成立：宿主缺失时
 * {@link #register} 返回 {@code false}，插件照常启动，只是对外占位符不可解析。</p>
 *
 * <p>注册目标是引擎的 {@link PlaceholderEngine#global() 全局注册表}：PAPI 兼容桥
 * 也镜像进同一张表，因此注册一次，PAPI 消费者（TAB / TrChat 等）用
 * {@code %identifier_params%} 同样能解析到。</p>
 */
public final class PlaceholderSupport {

    /**
     * 已就绪的引擎服务；仅成功时缓存（未就绪不缓存，宿主后加载时能自愈）。
     *
     * <p>缓存以 {@code ServicesManager} 实例为键 —— 服务管理器在单个服务端生命周期内
     * 恒定，换了服务端（如测试中反复 mock/unmock）则自动重新查找，无需测试专用重置钩子。</p>
     */
    private static volatile Object cachedManager;

    private static volatile PlaceholderService cachedService;

    private PlaceholderSupport() {}

    /**
     * 引擎宿主（PepperPlaceholder）是否已就绪。
     *
     * <p>任何失败（插件未安装、类缺失、服务未注册）都返回 {@code false}，
     * 绝不抛出。</p>
     */
    public static boolean available() {
        return service() != null;
    }

    /**
     * 把占位符注册进引擎全局注册表。
     *
     * <p>identifier <b>不能含 {@code _}</b>（首个 {@code _} 是 identifier/params
     * 切分点，多词用 {@code -}）；params 为不透明字符串，由 handler 自行解释。</p>
     *
     * <p>重复注册同名 identifier 时先注销旧的（热重载场景），不抛异常。</p>
     *
     * @param identifier 占位符标识符，如 {@code union}
     * @param handler 求值函数：{@code (params, player) -> value}；返回 {@code null}
     *     表示「本次解析不了」，引擎会保留占位符原文
     * @return 是否注册成功；宿主缺失或参数非法时为 {@code false}
     */
    public static boolean register(final String identifier, final BiFunction<String, OfflinePlayer, String> handler) {
        if (identifier == null || identifier.isEmpty() || handler == null) {
            return false;
        }
        if (identifier.indexOf('_') >= 0) {
            // 首个 _ 是 identifier/params 切分点；交给引擎会抛异常，这里软失败并留痕。
            log("占位符 identifier 不能含 '_'（切分点），请改用 '-'：" + identifier);
            return false;
        }
        final Registry registry = registry();
        if (registry == null) {
            return false; // 宿主缺失 → 降级，不抛
        }
        try {
            registry.unregister(identifier); // 热重载：先清旧的，避免重复注册抛异常
            registry.register(
                    identifier, PlaceholderEngine.Kind.SYNC, (params, ctx) -> handler.apply(params, player(ctx)));
            return true;
        } catch (final RuntimeException | LinkageError e) {
            log("注册占位符失败 " + identifier + "：" + e);
            return false;
        }
    }

    /**
     * 从引擎全局注册表注销占位符。
     *
     * @return 是否确实存在并已移除
     */
    public static boolean unregister(final String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        final Registry registry = registry();
        return registry != null && registry.unregister(identifier);
    }

    /** 当前已注册的 identifier 快照（宿主缺失时为空集）。 */
    public static Set<String> identifiers() {
        final Registry registry = registry();
        return registry == null ? Set.of() : registry.identifiers();
    }

    /**
     * 用引擎解析文本中的 {@code ${identifier_params}}。
     *
     * <p>未注册的标识符<b>原样保留</b>，因此调用期参数（i18n 内部占位符）与
     * 注册表占位符可以共存于同一段模板。</p>
     *
     * @param player 解析上下文玩家；可为 {@code null}（此时仍可解析与玩家无关的占位符）
     * @return 解析结果；宿主缺失、文本为 {@code null} 或不含 {@code ${}} 时原样返回
     */
    public static String resolve(final @Nullable OfflinePlayer player, final String text) {
        if (text == null || text.isEmpty() || text.indexOf(OPEN) < 0) {
            return text;
        }
        final PlaceholderService service = service();
        if (service == null) {
            return text; // 宿主缺失 → 原样返回，绝不中断消息渲染
        }
        try {
            return service.resolve(text, player == null ? Ctx.NONE : new Ctx(player));
        } catch (final RuntimeException | LinkageError e) {
            return text;
        }
    }

    // ==================== 内部 ====================

    /** 引擎定界符前缀（与 {@code PlaceholderEngine} 一致）。 */
    private static final String OPEN = "${";

    /** 全局注册表；宿主缺失时为 {@code null}。 */
    private static @Nullable Registry registry() {
        final PlaceholderService service = service();
        return service == null ? null : service.global();
    }

    /** 从解析上下文取玩家（非玩家上下文返回 {@code null}）。 */
    private static @Nullable OfflinePlayer player(final Ctx ctx) {
        return ctx != null && ctx.platform() instanceof OfflinePlayer p ? p : null;
    }

    private static void log(final String message) {
        try {
            Bukkit.getLogger().warning("[PepperPlaceholder] " + message);
        } catch (final Throwable ignored) {
            // 非 Bukkit 环境（单元测试）没有 logger，静默。
        }
    }

    /** 取引擎服务；任何失败返回 {@code null}（软依赖兜底）。 */
    private static @Nullable PlaceholderService service() {
        try {
            final Object manager = Bukkit.getServicesManager();
            if (manager == cachedManager) {
                return cachedService;
            }
            final PlaceholderService loaded = ((ServicesManager) manager).load(PlaceholderService.class);
            cachedManager = manager;
            cachedService = loaded;
            return loaded;
        } catch (final Throwable t) {
            // 宿主插件缺失时 load() 触发的 NoClassDefFoundError 也走这里。
            return null;
        }
    }
}
