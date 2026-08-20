package io.pepper.lib.task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 由 Bukkit 调度器支持的 {@link PepperScheduler} 实现（统一自两插件 PaperScheduler）。
 */
public final class BukkitPepperScheduler implements PepperScheduler {

    private final JavaPlugin plugin;

    public BukkitPepperScheduler(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void runTask(final Runnable task) {
        Bukkit.getScheduler().runTask(this.plugin, task);
    }

    @Override
    public void runAsync(final Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, task);
    }

    @Override
    public void runRepeating(final Runnable task, final long delayTicks, final long periodTicks) {
        Bukkit.getScheduler().runTaskTimer(this.plugin, task, delayTicks, periodTicks);
    }

    @Override
    public <T> CompletableFuture<T> supplyOnMain(final Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(
                supplier, task -> Bukkit.getScheduler().runTask(this.plugin, task));
    }
}
