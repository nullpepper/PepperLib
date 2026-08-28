package io.example.shaded;

import java.util.concurrent.CompletableFuture;
import ltd.pepper.lib.runtime.PepperLibRuntime;
import ltd.pepper.lib.task.PepperScheduler;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * shade 模式示例消费者（双模式重构文档 §9.4）：
 *
 * <ul>
 *   <li>源码按普通库坐标编写（{@code ltd.pepper.lib.*}），shadow 打包时 relocate
 *       到 {@code io.example.shaded.lib.*} 私有命名空间；</li>
 *   <li>不安装 PepperLib 前置插件，不使用 {@link PepperLibRuntime} 服务
 *       （shade 消费者自包含，接口类型与前置插件注册的互不相同）；</li>
 *   <li>本类只演示 {@link PepperScheduler} 使用，证明 relocate 后库可用。</li>
 * </ul>
 */
public class ShadedExamplePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        final PepperScheduler scheduler = new ltd.pepper.lib.task.BukkitPepperScheduler(this);
        CompletableFuture.supplyAsync(() -> "shaded-ok")
                .thenAccept(value -> scheduler.runTask(
                        () -> this.getLogger().info("ShadedExample: " + value + " (PepperLib relocated)")));
    }
}
