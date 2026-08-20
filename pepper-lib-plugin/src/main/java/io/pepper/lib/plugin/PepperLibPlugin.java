package io.pepper.lib.plugin;

import io.pepper.lib.runtime.PepperLibRuntime;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PepperLib 前置插件（双模式重构文档 §3.2 / §4）。
 *
 * <p>只负责共享库运行时的初始化和服务注册，<b>不拥有消费者业务状态</b>：
 * {@code GuiHost}、{@code LanguageBundle}、{@code MigrationRunner}、
 * {@code ConfirmRegistry}、{@code ThreadGuard.Instance} 均由每个消费者自行创建。
 * PlaceholderAPI / Vault 缺失时本插件必须能够启动（可选能力，与本插件无关）。</p>
 */
// 非 final：MockBukkit 测试以 ByteBuddy subclass 加载插件类（两插件主类为 final，
// 其测试使用非 final 桩类；本插件直接可加载，无需桩）。
public class PepperLibPlugin extends JavaPlugin {

    private PepperLibRuntime runtime;

    @Override
    public void onEnable() {
        // 版本单一来源：插件描述（plugin.yml 经 Gradle 注入根项目 version）。
        // 能力集按服务器版本决策（自适应加载 §2.2）：低于 1.21 不声明 gui-host。
        // getDescription()（远古 API）而非 getPluginMeta()（Paper 1.19.4+）——
        // 低版本服务器（1.18.2）上没有 getPluginMeta。
        final String minecraftVersion = Bukkit.getMinecraftVersion();
        this.runtime = new DefaultPepperLibRuntime(
                this.getDescription().getVersion(),
                CapabilityResolver.resolve(
                        minecraftVersion,
                        CapabilityAnnotationScanner.scan(getClass().getClassLoader())));
        Bukkit.getServicesManager().register(PepperLibRuntime.class, this.runtime, this, ServicePriority.Normal);
        if (!this.runtime.supports(PepperLibRuntime.CAP_GUI_HOST)) {
            this.getLogger()
                    .warning("服务器 " + minecraftVersion + " < 1.21：gui-host 特性已禁用"
                            + "（InventoryView 在 1.20.x 为 class、1.21+ 为 interface，形态不兼容）；"
                            + "消费者请查询 supports(\"gui-host\") 后跳过 GUI 功能");
        }
        this.getLogger()
                .info("PepperLib " + this.runtime.apiVersion()
                        + " 已启用（共享库前置；消费者经 ServicesManager 获取 PepperLibRuntime）");
    }

    @Override
    public void onDisable() {
        // 注销本插件注册的全部服务；消费者实例（GUI/确认/守卫）由消费者自己清理。
        Bukkit.getServicesManager().unregisterAll(this);
        this.runtime = null;
    }
}
