package ltd.pepper.lib.i18n;

import java.util.Objects;

/**
 * 格式化占位符的显式文本类型。
 *
 * <p>字面文本与 MiniMessage 文本在类型层面区分，杜绝用户内容被误解析为 MiniMessage
 * 标签 / 点击事件（自 PepperUnion TextValue 移植）。</p>
 *
 * <p><b>渲染契约（WP4 / PepperUnion L-3）</b>：外部来源的占位符值——跨插件 API 结果、
 * 玩家输入、公会名、领地名等——默认必须用 {@link #literal}；只有插件自有、内容可信的
 * 格式化指令才允许 {@link #mini}。把外部值按 mini 渲染即引入 MiniMessage 注入面。</p>
 */
public final class TextValue {

    private final String value;
    private final boolean literal;

    private TextValue(final String value, final boolean literal) {
        this.value = value == null ? "" : value;
        this.literal = literal;
    }

    /** 按纯文本渲染（不解析 MiniMessage）。 */
    public static TextValue literal(final String value) {
        return new TextValue(value, true);
    }

    /**
     * 按 MiniMessage 解析渲染（调用方必须确信内容可信且格式正确）。
     * 外部来源的值禁止用此工厂——见类契约，用 {@link #literal}。
     */
    public static TextValue mini(final String value) {
        return new TextValue(value, false);
    }

    public String value() {
        return this.value;
    }

    public boolean isLiteral() {
        return this.literal;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof TextValue other && this.literal == other.literal && this.value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.value, this.literal);
    }

    @Override
    public String toString() {
        return (this.literal ? "literal(" : "mini(") + this.value + ")";
    }
}
