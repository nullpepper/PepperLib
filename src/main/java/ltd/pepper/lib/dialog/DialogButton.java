package ltd.pepper.lib.dialog;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * 一个动作按钮的不可变装配：标签、可选悬停提示（tooltip）、自带业务值与点击回调。
 *
 * <p>每个按钮各自持有自己的回调闭包——业务值在装配时绑定，因此同一对话框内的不同按钮可以
 * 指向不同业务对象（例如“点了哪个称号”）。回调的线程模型与异常契约见 {@link DialogClick}，
 * 由 {@link DialogHost} 统一保证。</p>
 *
 * @param <V> 业务值类型
 */
public final class DialogButton<V> {

    private final Component label;
    private final Component tooltip;
    private final V value;
    private final DialogClick<V> click;

    DialogButton(final Component label, final Component tooltip, final V value, final DialogClick<V> click) {
        this.label = Objects.requireNonNull(label, "label");
        this.tooltip = tooltip;
        this.value = value;
        this.click = Objects.requireNonNull(click, "click");
    }

    /** 按钮文案。 */
    public Component label() {
        return this.label;
    }

    /** 悬停提示；未配置时为 {@code null}（Paper 侧不设置 tooltip）。 */
    public Component tooltip() {
        return this.tooltip;
    }

    /** 该按钮装配时绑定的业务值（可为 {@code null}）。 */
    public V value() {
        return this.value;
    }

    /**
     * 直接执行本按钮的回调——<b>不含</b>线程调度与异常守卫；生产路径一律由
     * {@link DialogHost} 守卫后调用。
     */
    void fire(final Player player) {
        this.click.onClick(this.value, player);
    }
}
