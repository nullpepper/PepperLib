package ltd.pepper.lib.aswm.provider;

import ltd.pepper.lib.task.BukkitPepperScheduler;
import ltd.pepper.lib.world.InstanceWorldService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PepperLib world-instance 前置 provider：纯内存实现
 * （{@link BukkitMemoryWorldService}，插件 tmp + 符号链接隔离）。
 *
 * <p>ASP/slime 支持已移除（服务器环境从未可用，构建依赖维护成本 > 收益）。</p>
 */
public final class PepperLibAswmProviderPlugin extends JavaPlugin {

    private InstanceWorldService service;

    @Override
    public void onEnable() {
        this.service = new BukkitMemoryWorldService(this, new BukkitPepperScheduler(this));
        Bukkit.getServicesManager().register(InstanceWorldService.class, this.service, this, ServicePriority.Normal);
        getLogger().info("已注册 InstanceWorldService（" + this.service.providerInfo() + "）");
    }

    @Override
    public void onDisable() {
        if (this.service instanceof BukkitMemoryWorldService bukkit) {
            bukkit.close();
        }
        this.service = null;
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
