package ltd.pepper.lib.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.registry.data.dialog.DialogBase;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.junit.jupiter.api.Test;

/**
 * {@link MultiActionDialog} 装配契约：字段接线、默认值、快照不可变与入参校验。
 *
 * <p>这些断言只覆盖可确定性验证的纯装配（不触碰 Paper 服务端实现）。</p>
 */
class MultiActionDialogTest {

    private static final Component TITLE = Component.text("选择称号");
    private static final Component LINE_ONE = Component.text("点击应用");
    private static final Component LINE_TWO = Component.text("第二行");

    @Test
    void builderWiresEveryDeclaredField() {
        final AtomicInteger exitRuns = new AtomicInteger();
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(TITLE)
                .body(LINE_ONE)
                .body(List.of(LINE_TWO))
                .columns(3)
                .pause(true)
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .exitButton(Component.text("关闭"), Component.text("不改变称号"), exitRuns::incrementAndGet)
                .button(Component.text("称号 A"), Component.text("悬浮提示 A"), "title_a", (value, player) -> {})
                .button(Component.text("称号 B"), "title_b", (value, player) -> {})
                .build();

        assertEquals(TITLE, dialog.title(), "标题必须原样接线");
        assertEquals(List.of(LINE_ONE, LINE_TWO), dialog.body(), "正文行按加入顺序接线");
        assertEquals(3, dialog.columns(), "columns 必须原样接线");
        assertTrue(dialog.pause(), "pause 必须原样接线");
        assertFalse(dialog.canCloseWithEscape(), "canCloseWithEscape 必须原样接线");
        assertEquals(DialogBase.DialogAfterAction.NONE, dialog.afterAction(), "afterAction 必须原样接线");

        assertEquals(2, dialog.buttons().size(), "按钮数量必须等于 button(...) 调用次数");
        assertEquals(Component.text("称号 A"), dialog.buttons().get(0).label());
        assertEquals(Component.text("悬浮提示 A"), dialog.buttons().get(0).tooltip());
        assertEquals("title_a", dialog.buttons().get(0).value());
        assertEquals(Component.text("称号 B"), dialog.buttons().get(1).label());
        assertNull(dialog.buttons().get(1).tooltip(), "未指定 tooltip 时为 null");
        assertEquals("title_b", dialog.buttons().get(1).value());

        final MultiActionDialog.ExitButton exit = dialog.exitButton();
        assertNotNull(exit, "配置了退出按钮后必须可见");
        assertEquals(Component.text("关闭"), exit.label());
        assertEquals(Component.text("不改变称号"), exit.tooltip());
        exit.action().run();
        assertEquals(1, exitRuns.get(), "退出按钮动作必须是调用方提供的闭包");
    }

    @Test
    void defaultsAreNeutralAndDocumented() {
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(TITLE)
                .button(Component.text("仅一个"), "a", (value, player) -> {})
                .build();

        assertEquals(MultiActionDialog.DEFAULT_COLUMNS, dialog.columns(), "默认列数 = 2");
        assertEquals(2, dialog.columns());
        assertFalse(dialog.pause(), "默认不暂停游戏");
        assertTrue(dialog.canCloseWithEscape(), "默认允许 Esc 关闭");
        assertEquals(DialogBase.DialogAfterAction.CLOSE, dialog.afterAction(), "默认动作后关闭");
        assertEquals(List.of(), dialog.body(), "默认无正文");
        assertNull(dialog.exitButton(), "默认无退出按钮");
        assertSame(MultiActionDialog.DEFAULT_CALLBACK_OPTIONS, dialog.options(), "默认复用统一回调选项实例（未配置时）");
    }

    @Test
    void exitButtonWithoutActionClosesOnly() {
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(TITLE)
                .button(Component.text("A"), "a", (value, player) -> {})
                .exitButton(Component.text("取消"))
                .build();

        final MultiActionDialog.ExitButton exit = dialog.exitButton();
        assertNotNull(exit);
        assertNull(exit.tooltip(), "只给 label 的退出按钮无 tooltip");
        assertNotNull(exit.action(), "未提供动作时是无操作而非 null");
        exit.action().run();
    }

    @Test
    void clickCallbackOptionsAreSingleUseAndTenMinutes() {
        assertEquals(
                Duration.ofMinutes(10),
                MultiActionDialog.DEFAULT_CALLBACK_OPTIONS.lifetime(),
                "默认有效期 10 分钟（与命令层确认窗口同量级）");
        assertEquals(1, MultiActionDialog.DEFAULT_CALLBACK_OPTIONS.uses(), "默认一次性触发");
        assertNotEquals(
                ClickCallback.UNLIMITED_USES,
                MultiActionDialog.DEFAULT_CALLBACK_OPTIONS.uses(),
                "对话框首次点击后即关闭，回调不得可重复触发");
    }

    @Test
    void customOptionsReplaceTheDefault() {
        final ClickCallback.Options custom = ClickCallback.Options.builder()
                .lifetime(Duration.ofSeconds(30))
                .uses(2)
                .build();
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(TITLE)
                .options(custom)
                .button(Component.text("A"), "a", (value, player) -> {})
                .build();

        assertSame(custom, dialog.options(), "显式 options 必须整体替换默认值");
    }

    @Test
    void builtDialogIsAnImmutableSnapshot() {
        final MultiActionDialog.Builder<String> builder = MultiActionDialog.<String>multiAction(TITLE)
                .body(LINE_ONE)
                .button(Component.text("A"), "a", (value, player) -> {});
        final MultiActionDialog<String> dialog = builder.build();

        builder.button(Component.text("B"), "b", (value, player) -> {}).body(LINE_TWO);

        assertEquals(1, dialog.buttons().size(), "build() 后继续使用 builder 不得改变已构建对话框");
        assertEquals(List.of(LINE_ONE), dialog.body());
        assertThrows(UnsupportedOperationException.class, () -> dialog.buttons().add(null), "buttons() 必须是不可变视图");
        assertThrows(UnsupportedOperationException.class, () -> dialog.body().add(LINE_TWO), "body() 必须是不可变视图");
    }

    @Test
    void rejectsDialogWithoutButtons() {
        final MultiActionDialog.Builder<String> builder = MultiActionDialog.<String>multiAction(TITLE);
        assertThrows(IllegalArgumentException.class, builder::build, "multi_action 至少需要一个按钮");
    }

    @Test
    void rejectsNonPositiveColumns() {
        final MultiActionDialog.Builder<String> builder = MultiActionDialog.<String>multiAction(TITLE)
                .columns(0)
                .button(Component.text("A"), "a", (value, player) -> {});
        assertThrows(IllegalArgumentException.class, builder::build, "columns 必须 >= 1");
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> MultiActionDialog.<String>multiAction(null), "标题不可为 null");
        final MultiActionDialog.Builder<String> builder = MultiActionDialog.<String>multiAction(TITLE);
        assertThrows(
                NullPointerException.class,
                () -> builder.button(null, "a", (value, player) -> {}),
                "按钮 label 不可为 null");
        assertThrows(NullPointerException.class, () -> builder.button(Component.text("A"), "a", null), "点击回调不可为 null");
    }
}
