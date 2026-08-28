package ltd.pepper.lib.aswm.provider;

import java.util.concurrent.atomic.AtomicReference;
import ltd.pepper.lib.world.WorldInstance;
import ltd.pepper.lib.world.WorldInstanceState;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * provider 侧 {@link WorldInstance} 实现：不可变标识 + 原子状态/活体世界引用。
 */
final class AswmWorldInstance implements WorldInstance {

    private final String instanceId;
    private final String worldName;
    private final String templateId;
    private final AtomicReference<WorldInstanceState> state = new AtomicReference<>(WorldInstanceState.LOADING);
    private final AtomicReference<World> world = new AtomicReference<>();

    AswmWorldInstance(final String instanceId, final String worldName, final String templateId) {
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

    /** 世界注册成功：LOADING → ACTIVE（仅主线程调用）。 */
    void activate(final World world) {
        this.world.set(world);
        this.state.set(WorldInstanceState.ACTIVE);
    }

    /** 卸载开始：ACTIVE → UNLOADING。 */
    void markUnloading() {
        this.state.set(WorldInstanceState.UNLOADING);
    }

    /** 卸载完成：→ UNLOADED。 */
    void markUnloaded() {
        this.state.set(WorldInstanceState.UNLOADED);
    }

    /** 失败：→ FAILED（实例记录保留供诊断）。 */
    void markFailed() {
        this.state.set(WorldInstanceState.FAILED);
    }
}
