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
        // 版本单一来源：插件描述（paper-plugin.yml 经 Gradle 注入根项目 version）。
        this.runtime = new DefaultPepperLibRuntime(this.getPluginMeta().getVersion());
        Bukkit.getServicesManager().register(PepperLibRuntime.class, this.runtime, this, ServicePriority.Normal);
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
