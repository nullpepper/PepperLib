package ltd.pepper.lib.dialog;

import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import ltd.pepper.lib.task.BukkitPepperScheduler;
import ltd.pepper.lib.task.PepperScheduler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 原生 Dialog 的打开与点击回调管线：主线程调度 + 异常隔离。
 *
 * <p><b>实例类</b>（非静态单例）：构造时拿 Plugin owner（与 {@code ltd.pepper.lib.gui.GuiHost}
 * 同约定）。PepperClaim 与 PepperUnion 同服共存时各自持有独立实例，插件把实例放在组合根字段里，
 * 不取任何全局插件引用。</p>
 *
 * <p><b>线程模型</b>：{@link #open} 与 {@link #callbackFor} / {@link #callbackForExit} 产出的
 * Paper 回调均可从任意线程调用；业务回调统一经 {@link PepperScheduler} 回 Minecraft 主线程执行
 * （已在主线程时直接执行，不额外排队）。</p>
 *
 * <p><b>异常契约</b>：业务回调抛出的任何 {@link Throwable}，以及调度失败（如插件已禁用），都被
 * 捕获并以 {@code WARNING} 记入插件日志，绝不上抛到服务端的对话框处理路径；非 {@link Player}
 * 的 audience 点击被丢弃并记日志（业务回调因此可以假定 player 非 null）。</p>
 */
public final class DialogHost {

    private final Plugin plugin;
    private final PepperScheduler scheduler;

    /**
     * 使用 Bukkit 调度器（{@link BukkitPepperScheduler}）的常规构造。
     *
     * @param plugin 插件 owner（调度与日志出口）
     */
    public DialogHost(final JavaPlugin plugin) {
        this(plugin, new BukkitPepperScheduler(plugin));
    }

    /**
     * 使用调用方提供的调度器（已有事件线程抽象，或测试注入）。
     *
     * @param plugin 插件 owner（日志出口）
     * @param scheduler 主线程调度器
     */
    public DialogHost(final Plugin plugin, final PepperScheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 向玩家打开一个多操作对话框。
     *
     * <p>可从任意线程调用：渲染（含 Paper 回调注册）与展示都在主线程完成。渲染或调度失败只记
     * 日志，不向调用方抛出。</p>
     *
     * @param player 目标玩家
     * @param dialog 装配好的对话框（不可变，可跨线程共享）
     */
    public void open(final Player player, final MultiActionDialog<?> dialog) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dialog, "dialog");
        runIsolated(
                () -> player.showDialog(PaperDialogs.render(dialog, this)),
                "打开对话框失败",
                () -> "player=" + player.getName() + ", buttons="
                        + dialog.buttons().size());
    }

    /**
     * 把某个业务按钮的点击适配为 Paper 回调：主线程执行 + 异常隔离。
     *
     * <p>包内实现细节，供 {@link PaperDialogs} 渲染时使用。</p>
     *
     * @param button 业务按钮
     * @return Paper 对话框回调
     */
    DialogActionCallback callbackFor(final DialogButton<?> button) {
        Objects.requireNonNull(button, "button");
        return (view, audience) -> guarded(audience, button.label(), button::fire);
    }

    /**
     * 把退出按钮的点击适配为 Paper 回调（同线程/异常契约）。
     *
     * @param exit 退出按钮
     * @return Paper 对话框回调
     */
    DialogActionCallback callbackForExit(final MultiActionDialog.ExitButton exit) {
        Objects.requireNonNull(exit, "exit");
        return (view, audience) ->
                guarded(audience, exit.label(), player -> exit.action().run());
    }

    /** 回主线程执行任务（已在主线程则直接执行）。 */
    private void dispatch(final Runnable task) {
        if (this.scheduler.isMainThread()) {
            task.run();
        } else {
            this.scheduler.runTask(task);
        }
    }

    /**
     * 业务回调的统一入口：audience 必须是玩家，随后「回主线程 + 异常隔离」执行。
     *
     * <p>连诊断路径本身（取按钮文案、取玩家名、写日志）都被兜底：异常隔离契约不允许有“只有
     * 诊断失败才会漏出去”的漏洞。</p>
     *
     * @param audience 服务端传入的点击者
     * @param label 按钮文案（仅用于日志诊断）
     * @param business 业务回调
     */
    private void guarded(final Audience audience, final Component label, final Consumer<Player> business) {
        try {
            if (!(audience instanceof Player player)) {
                warnQuietly("对话框点击来自非玩家 audience（已丢弃）：label=" + safeText(label) + ", audience=" + audience);
                return;
            }
            runIsolated(
                    () -> business.accept(player),
                    "对话框回调异常",
                    () -> "label=" + safeText(label) + ", player=" + player.getName());
        } catch (final Throwable guardError) {
            // 最后一道防线：守卫自身的异常同样不允许回到服务端的对话框处理路径。
            warnQuietly("对话框回调守卫自身异常（已隔离，未上抛）", guardError);
        }
    }

    /**
     * 执行一段业务代码并保证「主线程 + 异常不上抛」：调度失败与业务异常都被隔离记录。
     *
     * @param business 业务代码
     * @param message 日志前缀
     * @param context 日志上下文（延迟求值且自身绝不抛：诊断失败不得变成新的异常源）
     */
    private void runIsolated(final Runnable business, final String message, final Supplier<String> context) {
        final String detail = safeContext(context);
        try {
            dispatch(() -> {
                try {
                    business.run();
                } catch (final Throwable error) {
                    warnQuietly(message + "（已隔离，未上抛）： " + detail, error);
                }
            });
        } catch (final Throwable dispatchError) {
            warnQuietly(message + "（调度失败，已隔离）： " + detail, dispatchError);
        }
    }

    /** 日志出口：日志本身失败也只吞掉——异常隔离契约的最后一段。 */
    private void warnQuietly(final String message, final Throwable error) {
        try {
            final Logger logger = this.plugin.getLogger();
            if (logger != null) {
                logger.log(Level.WARNING, message, error);
            }
        } catch (final Throwable ignored) {
            // 记录诊断失败不能反过来成为新的异常源。
        }
    }

    private void warnQuietly(final String message) {
        try {
            final Logger logger = this.plugin.getLogger();
            if (logger != null) {
                logger.warning(message);
            }
        } catch (final Throwable ignored) {
            // 同上。
        }
    }

    /** 诊断上下文的求值兜底（如玩家句柄已失效时 {@code getName()} 抛异常）。 */
    private static String safeContext(final Supplier<String> context) {
        try {
            return context.get();
        } catch (final Throwable ignored) {
            return "<上下文不可用>";
        }
    }

    /** 按钮文案的纯文本形态（日志诊断；非 MiniMessage 的 legacy 解析），自身绝不抛。 */
    private static String safeText(final Component label) {
        try {
            return label == null
                    ? "-"
                    : PlainTextComponentSerializer.plainText().serialize(label);
        } catch (final Throwable ignored) {
            return "<文案不可用>";
        }
    }
}
