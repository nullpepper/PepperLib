package ltd.pepper.lib.aswm.provider;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import ltd.pepper.lib.task.BukkitPepperScheduler;
import ltd.pepper.lib.world.InstanceWorldService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PepperLib-ASWM-Provider 前置插件：把 {@link InstanceWorldService} 经 ServicesManager
 * 注册给消费者（PVP/PVE 竞技场插件）。
 *
 * <p>运行时探测：{@link AdvancedSlimePaperAPI#instance()} 不可用（普通 Paper 环境、
 * ASP 缺失/版本不兼容）时禁用自身并给出诊断——不影响 PepperLib 与其他插件，
 * 消费者将收到 {@code PROVIDER_UNAVAILABLE} 而非普通 Bukkit 世界降级。</p>
 */
public final class PepperLibAswmProviderPlugin extends JavaPlugin {

    private InstanceWorldService service;

    @Override
    public void onEnable() {
        // 1) 尝试 ASP（纯内存 slime 世界）
        final AdvancedSlimePaperAPI api;
        try {
            api = AdvancedSlimePaperAPI.instance();
        } catch (final Throwable t) {
            getLogger()
                    .warning("ASP 不可用（" + t.getClass().getSimpleName()
                            + "）→ 降级为 BukkitMemoryWorldService（插件 tmp + 符号链接隔离）");
            this.service = new BukkitMemoryWorldService(this, new BukkitPepperScheduler(this));
            Bukkit.getServicesManager()
                    .register(InstanceWorldService.class, this.service, this, ServicePriority.Normal);
            getLogger().info("已注册 InstanceWorldService（" + this.service.providerInfo() + "）");
            return;
        }
        this.service = new AswmInstanceWorldService(this, api, new BukkitPepperScheduler(this));
        Bukkit.getServicesManager().register(InstanceWorldService.class, this.service, this, ServicePriority.Normal);
        getLogger().info("已注册 InstanceWorldService（" + this.service.providerInfo() + "）");
    }

    @Override
    public void onDisable() {
        if (this.service != null) {
            // 主线程同步清理：卸载全部实例（不保存、强制、逐实例记录失败）。
            if (this.service instanceof AswmInstanceWorldService aswm) {
                aswm.close();
            } else if (this.service instanceof BukkitMemoryWorldService bukkit) {
                bukkit.close();
            }
            this.service = null;
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
