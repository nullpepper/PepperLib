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
     * 前置插件提供的 PepperLib API 版本（如 {@code "0.2.0"}，与发布坐标版本一致）。
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
