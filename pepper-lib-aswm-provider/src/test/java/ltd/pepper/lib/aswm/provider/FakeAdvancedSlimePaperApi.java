package ltd.pepper.lib.aswm.provider;

import static org.mockito.Mockito.mock;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.loaders.SlimeSerializationAdapter;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * 测试用 {@link AdvancedSlimePaperAPI} 假实现：readWorld 只校验 loader 中世界
 * 存在（不解析内容），loadWorld 用 MockBukkit 真实创建 Bukkit 世界——
 * 使 provider 生命周期可端到端测试而不依赖真实 ASP 环境。
 */
final class FakeAdvancedSlimePaperApi implements AdvancedSlimePaperAPI {

    @Override
    public SlimeWorld readWorld(
            final SlimeLoader loader, final String worldName, final boolean readOnly, final SlimePropertyMap properties)
            throws UnknownWorldException, IOException {
        if (!loader.worldExists(worldName)) {
            throw new UnknownWorldException(worldName);
        }
        return new FakeSlimeWorld(worldName, readOnly, properties, loader);
    }

    @Override
    public SlimeWorldInstance loadWorld(final SlimeWorld slimeWorld, final boolean readOnly) {
        if (Bukkit.getWorld(slimeWorld.getName()) != null) {
            throw new IllegalArgumentException("world already loaded: " + slimeWorld.getName());
        }
        final World world = Bukkit.createWorld(new WorldCreator(slimeWorld.getName()));
        if (world == null) {
            throw new IllegalArgumentException("failed to create world: " + slimeWorld.getName());
        }
        final SlimeWorldInstance instance = org.mockito.Mockito.mock(SlimeWorldInstance.class);
        org.mockito.Mockito.when(instance.getBukkitWorld()).thenReturn(world);
        return instance;
    }

    @Override
    public SlimeWorldInstance getLoadedWorld(final String worldName) {
        return null;
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return List.of();
    }

    @Override
    public boolean worldLoaded(final SlimeWorld slimeWorld) {
        return false;
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        return new SlimeSerializationAdapter() {
            @Override
            public byte[] serializeWorld(final SlimeWorld slimeWorld) {
                return new byte[0];
            }

            @Override
            public int getSlimeFormat() {
                return 0;
            }

            @Override
            public SlimeWorld deserializeWorld(
                    final String worldName,
                    final byte[] serializedWorld,
                    final SlimeLoader loader,
                    final SlimePropertyMap propertyMap,
                    final boolean readOnly)
                    throws java.io.IOException {
                return new FakeSlimeWorld(worldName, readOnly, propertyMap, loader);
            }
        };
    }

    @Override
    public void saveWorld(final SlimeWorld slimeWorld) throws IOException {
        throw new UnsupportedOperationException("fake api does not save");
    }

    @Override
    public void migrateWorld(final String worldName, final SlimeLoader from, final SlimeLoader to) {
        throw new UnsupportedOperationException("fake api does not migrate");
    }

    @Override
    public SlimeWorld createEmptyWorld(
            final String worldName,
            final boolean readOnly,
            final SlimePropertyMap properties,
            final SlimeLoader loader) {
        throw new UnsupportedOperationException("fake api does not create empty worlds");
    }

    @Override
    public SlimeWorld readVanillaWorld(final File worldDir, final String worldName, final SlimeLoader loader) {
        throw new UnsupportedOperationException("fake api does not import vanilla worlds");
    }

    /** 供测试确认 readWorld 实际被调用。 */
    static PersistentDataContainer fakePersistentDataContainer() {
        return mock(PersistentDataContainer.class);
    }
}
