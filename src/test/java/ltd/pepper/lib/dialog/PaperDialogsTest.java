package ltd.pepper.lib.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.pepper.lib.task.PepperScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PaperDialogs} 装配契约：模型字段必须如实落到 Paper 的
 * {@code DialogBase} / {@code ActionButton} / {@code MultiActionType} 构建器上。
 *
 * <p>为什么用 mock 的 {@link DialogInstancesProvider}：Paper 的 Dialog 构件只能经
 * 服务端实现的 provider 构造（{@code DialogInstancesProvider.instance()} 走
 * {@code ServiceLoader}，paper-api 内无 provider），单元测试里无法构造真实
 * {@code Dialog}。这里校验的是<b>接线</b>（哪个字段进了哪个 builder、回调注册了几次、
 * 注册的回调是否仍带业务值），真实打开效果不在单测范围内。</p>
 */
class PaperDialogsTest {

    private static final Component TITLE = Component.text("选择称号");
    private static final Component LINE = Component.text("点击应用");
    private static final Component LABEL_A = Component.text("称号 A");
    private static final Component LABEL_B = Component.text("称号 B");
    private static final Component TOOLTIP_A = Component.text("悬浮提示 A");
    private static final Component EXIT_LABEL = Component.text("关闭");
    private static final Component EXIT_TOOLTIP = Component.text("不改变称号");

    /** 每个 test 内部构造：provider 与其产物 builder 的 mock 集合。 */
    private static final class Fixture {

        final DialogInstancesProvider provider = mock(DialogInstancesProvider.class);
        final DialogBase.Builder baseBuilder = mock(DialogBase.Builder.class);
        final MultiActionType.Builder typeBuilder = mock(MultiActionType.Builder.class);
        final RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder> factory;
        final DialogRegistryEntry.Builder entry = mock(DialogRegistryEntry.Builder.class);
        final ActionButton.Builder builderA = mock(ActionButton.Builder.class);
        final ActionButton.Builder builderB = mock(ActionButton.Builder.class);
        final ActionButton.Builder builderExit = mock(ActionButton.Builder.class);
        final ActionButton buttonA = mock(ActionButton.class);
        final ActionButton buttonB = mock(ActionButton.class);
        final ActionButton actionExit = mock(ActionButton.class);
        final DialogAction.CustomClickAction registeredA = mock(DialogAction.CustomClickAction.class);
        final DialogAction.CustomClickAction registeredB = mock(DialogAction.CustomClickAction.class);
        final DialogAction.CustomClickAction registeredExit = mock(DialogAction.CustomClickAction.class);
        final DialogBase base = mock(DialogBase.class);
        final MultiActionType type = mock(MultiActionType.class);
        final PlainMessageDialogBody body = mock(PlainMessageDialogBody.class);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture() {
            this.factory = mock(RegistryBuilderFactory.class);

            when(this.provider.dialogBaseBuilder(TITLE)).thenReturn(this.baseBuilder);
            when(this.provider.plainMessageDialogBody(LINE)).thenReturn(this.body);
            when(this.provider.actionButtonBuilder(LABEL_A)).thenReturn(this.builderA);
            when(this.provider.actionButtonBuilder(LABEL_B)).thenReturn(this.builderB);
            when(this.provider.actionButtonBuilder(EXIT_LABEL)).thenReturn(this.builderExit);
            when(this.provider.register(any(), any()))
                    .thenReturn(this.registeredA, this.registeredB, this.registeredExit);
            when(this.provider.multiAction(anyList())).thenReturn(this.typeBuilder);

            when(this.baseBuilder.pause(anyBoolean())).thenReturn(this.baseBuilder);
            when(this.baseBuilder.canCloseWithEscape(anyBoolean())).thenReturn(this.baseBuilder);
            when(this.baseBuilder.afterAction(any())).thenReturn(this.baseBuilder);
            when(this.baseBuilder.body(any())).thenReturn(this.baseBuilder);
            when(this.baseBuilder.inputs(any())).thenReturn(this.baseBuilder);
            when(this.baseBuilder.build()).thenReturn(this.base);

            when(this.builderA.tooltip(any())).thenReturn(this.builderA);
            when(this.builderA.action(any())).thenReturn(this.builderA);
            when(this.builderA.build()).thenReturn(this.buttonA);
            when(this.builderB.action(any())).thenReturn(this.builderB);
            when(this.builderB.build()).thenReturn(this.buttonB);
            when(this.builderExit.tooltip(any())).thenReturn(this.builderExit);
            when(this.builderExit.action(any())).thenReturn(this.builderExit);
            when(this.builderExit.build()).thenReturn(this.actionExit);

            when(this.typeBuilder.columns(anyInt())).thenReturn(this.typeBuilder);
            when(this.typeBuilder.exitAction(any())).thenReturn(this.typeBuilder);
            when(this.typeBuilder.build()).thenReturn(this.type);

            doReturn(this.entry).when(this.factory).empty();
            when(this.entry.base(any())).thenReturn(this.entry);
            when(this.entry.type(any())).thenReturn(this.entry);
        }
    }

    /** 字段全开的模型 + 记录业务回调与退出动作。 */
    private static MultiActionDialog<String> fullModel(final List<String> fired, final AtomicInteger exitRuns) {
        return MultiActionDialog.<String>multiAction(TITLE)
                .body(LINE)
                .columns(3)
                .pause(true)
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .exitButton(EXIT_LABEL, EXIT_TOOLTIP, exitRuns::incrementAndGet)
                .button(LABEL_A, TOOLTIP_A, "vA", (value, player) -> fired.add(value))
                .button(LABEL_B, "vB", (value, player) -> fired.add(value))
                .build();
    }

    private static DialogHost hostOf() {
        final PepperScheduler scheduler = mock(PepperScheduler.class);
        when(scheduler.isMainThread()).thenReturn(true);
        return new DialogHost(mock(Plugin.class), scheduler);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyWiresModelFieldsIntoPaperBuilders() {
        final List<String> fired = new ArrayList<>();
        final AtomicInteger exitRuns = new AtomicInteger();
        final MultiActionDialog<String> model = fullModel(fired, exitRuns);
        final Fixture fixture = new Fixture();

        PaperDialogs.apply(fixture.factory, fixture.provider, model, hostOf());

        // 标题 / base 开关 / 正文（plain message body，一行一个）
        verify(fixture.provider).dialogBaseBuilder(TITLE);
        verify(fixture.baseBuilder).pause(true);
        verify(fixture.baseBuilder).canCloseWithEscape(false);
        verify(fixture.baseBuilder).afterAction(DialogBase.DialogAfterAction.NONE);
        verify(fixture.baseBuilder).inputs(List.of());
        final ArgumentCaptor<List<DialogBody>> bodies = ArgumentCaptor.forClass(List.class);
        verify(fixture.baseBuilder).body(bodies.capture());
        assertEquals(List.of(fixture.body), bodies.getValue(), "正文行必须逐个渲染为 plain message body");
        verify(fixture.baseBuilder).build();

        // 按钮 label / tooltip
        verify(fixture.provider).actionButtonBuilder(LABEL_A);
        verify(fixture.provider).actionButtonBuilder(LABEL_B);
        verify(fixture.provider).actionButtonBuilder(EXIT_LABEL);
        verify(fixture.builderA).tooltip(TOOLTIP_A);
        verify(fixture.builderB, never()).tooltip(any());
        verify(fixture.builderExit).tooltip(EXIT_TOOLTIP);

        // multiAction：业务按钮进 actions，退出按钮只进 exitAction
        final ArgumentCaptor<List<ActionButton>> actions = ArgumentCaptor.forClass(List.class);
        verify(fixture.provider).multiAction(actions.capture());
        assertEquals(List.of(fixture.buttonA, fixture.buttonB), actions.getValue(), "退出按钮不得混入 actions");
        verify(fixture.typeBuilder).exitAction(fixture.actionExit);
        verify(fixture.typeBuilder).columns(3);
        verify(fixture.typeBuilder).build();

        // 装配进 registry entry
        verify(fixture.factory).empty();
        verify(fixture.entry).base(fixture.base);
        verify(fixture.entry).type(fixture.type);
    }

    @Test
    void applyRegistersOneSingleUseCallbackPerButtonAndKeepsItsValueBinding() {
        final List<String> fired = new ArrayList<>();
        final MultiActionDialog<String> model = fullModel(fired, new AtomicInteger());

        // 每个按钮（含退出）各注册一次；全部使用模型的统一回调选项（10 分钟 / 一次性）
        final ArgumentCaptor<DialogActionCallback> callbacks = ArgumentCaptor.forClass(DialogActionCallback.class);
        final Fixture fixture = new Fixture();
        PaperDialogs.apply(fixture.factory, fixture.provider, model, hostOf());
        verify(fixture.provider, times(3))
                .register(callbacks.capture(), eq(MultiActionDialog.DEFAULT_CALLBACK_OPTIONS));
        assertEquals(1, MultiActionDialog.DEFAULT_CALLBACK_OPTIONS.uses(), "注册的选项必须是一次性");
        verify(fixture.builderA).action(fixture.registeredA);
        verify(fixture.builderB).action(fixture.registeredB);
        verify(fixture.builderExit).action(fixture.registeredExit);

        // 注册进 Paper 的回调仍是带业务值的那一个（顺序：A、B、退出）
        callbacks.getAllValues().get(1).accept(null, mock(Player.class));
        assertEquals(List.of("vB"), fired, "Paper 侧回调必须仍然绑定到第 2 个按钮的业务值");
    }
}
