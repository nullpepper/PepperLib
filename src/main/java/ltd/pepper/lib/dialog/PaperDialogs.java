package ltd.pepper.lib.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/**
 * 装配模型 → Paper 原生 {@link Dialog} 的渲染（包内实现细节）。
 *
 * <p>所有 Paper 侧构件都经<b>显式传入</b>的 {@link DialogInstancesProvider} 构造，而不是用
 * {@code DialogAction.customClick} / {@code DialogBody.plainMessage} 等静态便捷方法：后者内部
 * 各自调用 {@code DialogInstancesProvider.instance()}（{@code ServiceLoader} 解析，需服务端实现），
 * 无法在无服务端环境下替换。显式传参让装配接线可被测试。</p>
 */
final class PaperDialogs {

    private PaperDialogs() {}

    /**
     * 生产渲染入口：取服务端 provider 并构建 Dialog。
     *
     * <p>每次调用都会为每个按钮（含退出按钮）向服务端注册一个新的回调 ID——不要在高频路径
     * 反复渲染同一模型。</p>
     *
     * @param model 装配模型
     * @param host 回调管线（主线程调度 + 异常隔离）
     * @return Paper 原生对话框
     */
    static Dialog render(final MultiActionDialog<?> model, final DialogHost host) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(host, "host");
        return Dialog.create(factory -> apply(factory, DialogInstancesProvider.instance(), model, host));
    }

    /**
     * 装配核心（可在测试中用 mock 的 provider/factory 校验接线）。
     *
     * @param factory Paper 的 RegistryBuilder 工厂（{@code Dialog.create} 回传的那个）
     * @param provider Paper 的 dialog 构件提供者
     * @param model 装配模型
     * @param host 回调管线
     */
    static void apply(
            final RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder> factory,
            final DialogInstancesProvider provider,
            final MultiActionDialog<?> model,
            final DialogHost host) {
        final DialogRegistryEntry.Builder entry = factory.empty();
        entry.base(base(provider, model)).type(type(provider, model, host));
    }

    /** 标题 / 三个开关 / 正文（每行一个 plain message body）/ 无输入项。 */
    private static DialogBase base(final DialogInstancesProvider provider, final MultiActionDialog<?> model) {
        return provider.dialogBaseBuilder(model.title())
                .pause(model.pause())
                .canCloseWithEscape(model.canCloseWithEscape())
                .afterAction(model.afterAction())
                .body(bodies(provider, model))
                .inputs(List.of())
                .build();
    }

    /** 业务按钮进 actions，退出按钮只进 exitAction 并显示在对话框下方。 */
    private static MultiActionType type(
            final DialogInstancesProvider provider, final MultiActionDialog<?> model, final DialogHost host) {
        final List<ActionButton> actions = new ArrayList<>(model.buttons().size());
        for (final DialogButton<?> button : model.buttons()) {
            actions.add(actionButton(provider, button, model.options(), host));
        }
        final MultiActionType.Builder type = provider.multiAction(actions).columns(model.columns());
        if (model.exitButton() != null) {
            type.exitAction(exitButton(provider, model.exitButton(), model.options(), host));
        }
        return type.build();
    }

    private static List<DialogBody> bodies(final DialogInstancesProvider provider, final MultiActionDialog<?> model) {
        final List<DialogBody> bodies = new ArrayList<>(model.body().size());
        for (final Component line : model.body()) {
            bodies.add(provider.plainMessageDialogBody(line));
        }
        return bodies;
    }

    private static ActionButton actionButton(
            final DialogInstancesProvider provider,
            final DialogButton<?> button,
            final ClickCallback.Options options,
            final DialogHost host) {
        final ActionButton.Builder builder = provider.actionButtonBuilder(button.label());
        if (button.tooltip() != null) {
            builder.tooltip(button.tooltip());
        }
        return builder.action(provider.register(host.callbackFor(button), options))
                .build();
    }

    private static ActionButton exitButton(
            final DialogInstancesProvider provider,
            final MultiActionDialog.ExitButton exit,
            final ClickCallback.Options options,
            final DialogHost host) {
        final ActionButton.Builder builder = provider.actionButtonBuilder(exit.label());
        if (exit.tooltip() != null) {
            builder.tooltip(exit.tooltip());
        }
        return builder.action(provider.register(host.callbackForExit(exit), options))
                .build();
    }
}
