package ltd.pepper.lib.dialog;

import io.papermc.paper.registry.data.dialog.DialogBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/**
 * 「多操作按钮」对话框的不可变装配（对应 Paper 的 {@code multi_action} 类型）：标题、可选正文行、
 * 一组按钮、列数、可选退出按钮，以及 {@code pause} / {@code canCloseWithEscape} / {@code afterAction}
 * 三个基础开关。
 *
 * <p><b>实例类</b>：模型本身无状态、不可变且线程安全；打开动作由 {@link DialogHost} 承担
 * （它持有 Plugin owner 并用其调度器回主线程），本类不取任何全局单例。</p>
 *
 * <p><b>与 Paper 的关系</b>：本类只是可检查的装配模型，不触达服务端注册表；渲染为 Paper
 * {@code Dialog} 发生在 {@link DialogHost#open} 内部（每次打开都会为按钮重新注册回调 ID，
 * 因此不要在高频路径反复打开/渲染）。</p>
 *
 * <p><b>默认值</b>：{@code columns = 2}、{@code pause = false}、{@code canCloseWithEscape = true}、
 * {@code afterAction = CLOSE}、无正文、无退出按钮、回调选项 {@link #DEFAULT_CALLBACK_OPTIONS}。
 * 暂停/锁 Esc 这类强约束由消费者显式打开（{@code pause(true)} / {@code canCloseWithEscape(false)}）。</p>
 *
 * @param <V> 按钮业务值类型（同一对话框内各按钮一致）
 */
public final class MultiActionDialog<V> {

    /**
     * 统一回调有效期：lifetime 10 分钟、uses 1。
     *
     * <p><b>为什么不可重复触发</b>：对话框在首次点击后按 {@code afterAction} 关闭，回调随后即
     * 失去意义；允许同一回调多次触发只会留下「同一按钮被重复投递」的双重执行风险（与
     * PepperUnion {@code DialogForms} 对资金/不可逆操作的一次性要求一致）。有效期 10 分钟与
     * 命令层确认窗口同量级，足够玩家阅读后点击。</p>
     */
    public static final ClickCallback.Options DEFAULT_CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .lifetime(Duration.ofMinutes(10))
            .uses(1)
            .build();

    /** 默认列数（原版 multi_action 两列）。 */
    public static final int DEFAULT_COLUMNS = 2;

    private final Component title;
    private final List<Component> body;
    private final List<DialogButton<V>> buttons;
    private final int columns;
    private final ExitButton exitButton;
    private final boolean pause;
    private final boolean canCloseWithEscape;
    private final DialogBase.DialogAfterAction afterAction;
    private final ClickCallback.Options options;

    private MultiActionDialog(final Builder<V> builder) {
        this.title = builder.title;
        this.body = List.copyOf(builder.body);
        this.buttons = List.copyOf(builder.buttons);
        this.columns = builder.columns;
        this.exitButton = builder.exitButton;
        this.pause = builder.pause;
        this.canCloseWithEscape = builder.canCloseWithEscape;
        this.afterAction = builder.afterAction;
        this.options = builder.options;
    }

    /**
     * 开始装配一个多操作对话框。
     *
     * @param title 对话框标题（不可为 null）
     * @param <V> 按钮业务值类型
     * @return 构建器（可变；{@link Builder#build()} 产出不可变快照）
     */
    public static <V> Builder<V> multiAction(final Component title) {
        return new Builder<>(title);
    }

    /** 标题。 */
    public Component title() {
        return this.title;
    }

    /** 正文行（每行渲染为一个 plain message body）；空列表表示无正文。 */
    public List<Component> body() {
        return this.body;
    }

    /** 动作按钮（不可变视图，按装配顺序）；至少一个。 */
    public List<DialogButton<V>> buttons() {
        return this.buttons;
    }

    /** 按钮列数（&gt;= 1）。 */
    public int columns() {
        return this.columns;
    }

    /** 退出/关闭按钮；未配置时为 {@code null}。 */
    public ExitButton exitButton() {
        return this.exitButton;
    }

    /** 是否在对话框打开期间暂停游戏（默认 false）。 */
    public boolean pause() {
        return this.pause;
    }

    /** 是否允许 Esc 关闭（默认 true）。 */
    public boolean canCloseWithEscape() {
        return this.canCloseWithEscape;
    }

    /** 动作执行后的行为（默认 {@code CLOSE}）。 */
    public DialogBase.DialogAfterAction afterAction() {
        return this.afterAction;
    }

    /** 本对话框所有按钮（含退出按钮）统一的回调有效期。 */
    public ClickCallback.Options options() {
        return this.options;
    }

    /**
     * 多操作对话框构建器：先加正文与按钮，最后 {@link #build()}。
     *
     * <p>非线程安全（构建期单线程使用）；{@link #build()} 之后仍可继续复用本构建器，
     * 已构建的对话框是独立快照。</p>
     *
     * @param <V> 按钮业务值类型
     */
    public static final class Builder<V> {

        private final Component title;
        private final List<Component> body = new ArrayList<>();
        private final List<DialogButton<V>> buttons = new ArrayList<>();
        private int columns = DEFAULT_COLUMNS;
        private ExitButton exitButton;
        private boolean pause = false;
        private boolean canCloseWithEscape = true;
        private DialogBase.DialogAfterAction afterAction = DialogBase.DialogAfterAction.CLOSE;
        private ClickCallback.Options options = DEFAULT_CALLBACK_OPTIONS;

        private Builder(final Component title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        /** 追加一行正文（plain message body）。 */
        public Builder<V> body(final Component line) {
            this.body.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /** 追加多行正文（按列表顺序）。 */
        public Builder<V> body(final List<Component> lines) {
            Objects.requireNonNull(lines, "lines").forEach(this::body);
            return this;
        }

        /** 设置按钮列数（&gt;= 1）；默认 {@link #DEFAULT_COLUMNS}。越界值在 {@link #build()} 被拒。 */
        public Builder<V> columns(final int columns) {
            this.columns = columns;
            return this;
        }

        /** 打开期间是否暂停游戏；默认 false。 */
        public Builder<V> pause(final boolean pause) {
            this.pause = pause;
            return this;
        }

        /** 是否允许 Esc 关闭；默认 true。 */
        public Builder<V> canCloseWithEscape(final boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return this;
        }

        /** 动作执行后的行为；默认 {@code CLOSE}。 */
        public Builder<V> afterAction(final DialogBase.DialogAfterAction afterAction) {
            this.afterAction = Objects.requireNonNull(afterAction, "afterAction");
            return this;
        }

        /** 本对话框所有按钮统一的回调有效期；默认 {@link #DEFAULT_CALLBACK_OPTIONS}。 */
        public Builder<V> options(final ClickCallback.Options options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** 追加一个无悬停提示的按钮。 */
        public Builder<V> button(final Component label, final V value, final DialogClick<V> click) {
            return button(label, null, value, click);
        }

        /**
         * 追加一个按钮。
         *
         * @param label 按钮文案（不可为 null）
         * @param tooltip 悬停提示；可为 {@code null}（Paper 侧不设置 tooltip）
         * @param value 业务值（随按钮绑定，可为 null）
         * @param click 点击回调（不可为 null）
         */
        public Builder<V> button(
                final Component label, final Component tooltip, final V value, final DialogClick<V> click) {
            this.buttons.add(new DialogButton<>(label, tooltip, value, click));
            return this;
        }

        /** 配置一个只关闭对话框的退出按钮（无 tooltip、无业务动作）。 */
        public Builder<V> exitButton(final Component label) {
            return exitButton(label, null, null);
        }

        /**
         * 配置退出/关闭按钮。
         *
         * @param label 按钮文案（不可为 null）
         * @param tooltip 悬停提示；可为 {@code null}
         * @param action 退出动作（可为 {@code null} = 无操作）；与业务按钮同契约：主线程执行、
         *     异常被隔离记录
         */
        public Builder<V> exitButton(final Component label, final Component tooltip, final Runnable action) {
            this.exitButton = new ExitButton(label, tooltip, action);
            return this;
        }

        /**
         * 产出不可变快照。
         *
         * @throws IllegalArgumentException 没有按钮，或 {@code columns < 1}
         */
        public MultiActionDialog<V> build() {
            if (this.buttons.isEmpty()) {
                throw new IllegalArgumentException("multiAction 对话框至少需要一个按钮（Paper multi_action 不接受空 actions）");
            }
            if (this.columns < 1) {
                throw new IllegalArgumentException("columns 必须 >= 1，实际为 " + this.columns);
            }
            return new MultiActionDialog<>(this);
        }
    }

    /** 退出/关闭按钮装配（无业务值，因此不参与按钮业务值类型 {@code V}）。 */
    public static final class ExitButton {

        private final Component label;
        private final Component tooltip;
        private final Runnable action;

        private ExitButton(final Component label, final Component tooltip, final Runnable action) {
            this.label = Objects.requireNonNull(label, "label");
            this.tooltip = tooltip;
            this.action = action == null ? () -> {} : action;
        }

        /** 按钮文案。 */
        public Component label() {
            return this.label;
        }

        /** 悬停提示；未配置时为 {@code null}。 */
        public Component tooltip() {
            return this.tooltip;
        }

        /** 退出动作（非 null；未提供时为无操作，仅按 {@code afterAction} 关闭对话框）。 */
        public Runnable action() {
            return this.action;
        }
    }
}
