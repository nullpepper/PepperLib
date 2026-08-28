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
     * world-instance 特性能力名（{@code io.pepper.lib.world.InstanceWorldService} 及
     * 相关类型）。公共签名只引用稳定 Bukkit 类型（{@code org.bukkit.World}），
     * 最低版本取库基线 1.18。注意：本能力只声明「库内 API 存在」；provider 是否
     * 可用需另经 ServicesManager 探测 {@code InstanceWorldService} 注册。
     */
    String CAP_WORLD_INSTANCE = "world-instance";

    /**
     * 前置插件提供的 PepperLib API 版本（如 {@code "0.5.0"}，与发布坐标版本一致）。
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

    /**
     * 运行时 API 版本是否达到最低要求（版本比较语义，设计评审 §4.3）。
     *
     * <p>替代消费者侧 {@code apiVersion().startsWith(...)} 前缀校验：0.x 阶段前缀近似
     * minor 契约，1.0 冻结后破坏性 major 升级会被前缀误放行。本方法按「实际 &gt;= 最低」
     * 分段数值比较（缺段按 0 补，如 {@code "0.5.0"} 与 {@code "0.5"} 等价）。</p>
     *
     * <p><b>兼容注意</b>：本方法由 0.6.0 引入，属 default 方法（japicmp 纯增量）；
     * 但旧版 lib（0.5.x 及更早）运行时不存在该方法，调用即 {@code NoSuchMethodError}——
     * 前置模式消费者编译期依赖版本即运行时最低要求，升级 lib 与消费者必须同步
     * （发版顺序：lib 先行，消费者随后）。</p>
     *
     * @param minimumApiVersion 消费者声明的最低 API 版本（如 {@code "0.5"}）
     * @return 运行时版本是否达到最低要求
     * @throws IllegalArgumentException 任一输入非法
     */
    default boolean atLeast(String minimumApiVersion) {
        return LibVersions.atLeast(apiVersion(), minimumApiVersion);
    }
}
