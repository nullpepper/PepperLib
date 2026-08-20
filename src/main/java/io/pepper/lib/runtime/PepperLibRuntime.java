package io.pepper.lib.runtime;

/**
 * PepperLib 前置插件注册的稳定运行时服务（双模式重构文档 §4）。
 *
 * <p>前置模式消费者在 {@code onEnable} 早期经 {@code org.bukkit.plugin.ServicesManager}
 * 获取本服务做版本与能力诊断，不依赖前置插件主类（{@code PepperLibPlugin}）的具体实现。</p>
 *
 * <p><b>shade 模式消费者不使用本接口</b>：其 relocate 后的 {@code io.pepper.lib.*}
 * 与服务器前置插件注册的接口是不同类型，按 shade 契约自包含运行。</p>
 */
public interface PepperLibRuntime {

    /**
     * gui-host 特性能力名（{@code GuiHolder} / {@code GuiHost} / {@code PageHolderAdapter}）。
     *
     * <p>低版本服务器（{@code Bukkit.getMinecraftVersion()} &lt; 1.21，{@code InventoryView}
     * 接口化之前）不声明此能力：这些类的公共签名引用 {@code InventoryView}，而该类型
     * 在 1.20.x 为 class、1.21+ 为 interface——形态不匹配的运行时执行会抛
     * {@code IncompatibleClassChangeError}。消费者必须查询本能力后再决定是否启用 gui-host。</p>
     */
    String CAP_GUI_HOST = "gui-host";

    /**
     * 前置插件提供的 PepperLib API 版本（如 {@code "0.3.0"}，与发布坐标版本一致）。
     *
     * @return 版本号字符串
     */
    String apiVersion();

    /**
     * 前置插件是否支持指定能力。
     *
     * @param capability 能力名；未知/未声明能力返回 {@code false}
     * @return 能力可用性
     */
    boolean supports(String capability);
}
