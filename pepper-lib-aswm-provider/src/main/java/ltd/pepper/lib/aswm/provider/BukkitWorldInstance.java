package ltd.pepper.lib.aswm.provider;

import java.util.concurrent.atomic.AtomicReference;
import ltd.pepper.lib.world.WorldInstance;
import ltd.pepper.lib.world.WorldInstanceState;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Bukkit 内存语义的 {@link WorldInstance} 实现：实例世界的状态容器，
 * 由 {@link BukkitMemoryWorldService} 创建并持有。
 */
final class BukkitWorldInstance implements WorldInstance {

    private final String instanceId;
    private final String worldName;
    private final String templateId;
    private final AtomicReference<WorldInstanceState> state = new AtomicReference<>(WorldInstanceState.LOADING);
    private final AtomicReference<World> world = new AtomicReference<>();

    BukkitWorldInstance(final String instanceId, final String worldName, final String templateId) {
        this.instanceId = instanceId;
        this.worldName = worldName;
        this.templateId = templateId;
    }

    @Override
    public String instanceId() {
        return this.instanceId;
    }

    @Override
    public String worldName() {
        return this.worldName;
    }

    @Override
    public String templateId() {
        return this.templateId;
    }

    @Override
    public World world() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("world() must be called on the Minecraft main thread");
        }
        return this.world.get();
    }

    @Override
    public WorldInstanceState state() {
        return this.state.get();
    }

    void activate(final World world) {
        this.world.set(world);
        this.state.set(WorldInstanceState.ACTIVE);
    }

    void markUnloading() {
        this.state.set(WorldInstanceState.UNLOADING);
    }

    void markUnloaded() {
        this.state.set(WorldInstanceState.UNLOADED);
    }

    void markFailed() {
        this.state.set(WorldInstanceState.FAILED);
    }
}
