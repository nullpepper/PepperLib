package ltd.pepper.lib.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级最低服务器版本声明（注解驱动能力决策，替代手写阈值分支）。
 *
 * <p>标注在 {@code ltd.pepper.lib} 的类上，声明该类可安全使用的<b>最低</b>
 * Minecraft 服务器版本。前置插件启动时扫描全部库类的注解，构建
 * {@code capability → 最低版本} 注册表，再按服务器实际版本决策能力集——
 * 消费者查询 {@link PepperLibRuntime#supports(String)} 的契约不变。</p>
 *
 * <p><b>示例</b>：{@code GuiHolder} / {@code GuiHost} / {@code PageHolderAdapter}
 * 的公共签名引用 {@code InventoryView}（1.20.x 为 class、1.21+ 为 interface，
 * 形态不匹配的运行时执行抛 {@code IncompatibleClassChangeError}），故标注
 * {@code @MinMinecraftVersion(value = "1.21", capability = "gui-host")}。</p>
 *
 * <p>只表达下限（无上限/区间语义）；同 {@link #capability()} 的所有类必须标注
 * 一致阈值（守卫测试保证），扫描器对冲突取最严值兜底。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MinMinecraftVersion {

    /**
     * 最低 Minecraft 版本（如 {@code "1.21"}）；新旧格式（{@code "1.18.2"} /
     * {@code "26.1.2"}）均可，由 {@link ServerVersions#parse(String)} 解析比较。
     *
     * @return 最低版本字符串
     */
    String value();

    /**
     * 该最低版本归属的能力名（如 {@link PepperLibRuntime#CAP_GUI_HOST}）。
     *
     * @return 能力名
     */
    String capability();
}
