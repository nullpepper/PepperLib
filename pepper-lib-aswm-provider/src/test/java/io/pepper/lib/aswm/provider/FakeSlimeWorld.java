package io.pepper.lib.aswm.provider;

import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * 测试用 {@link SlimeWorld} 假实现：clone 生成同名新实例（共享属性/加载器）。
 */
final class FakeSlimeWorld implements SlimeWorld {

    private final String name;
    private final boolean readOnly;
    private final SlimePropertyMap propertyMap;
    private final SlimeLoader loader;

    FakeSlimeWorld(
            final String name, final boolean readOnly, final SlimePropertyMap propertyMap, final SlimeLoader loader) {
        this.name = name;
        this.readOnly = readOnly;
        this.propertyMap = propertyMap;
        this.loader = loader;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public SlimeLoader getLoader() {
        return this.loader;
    }

    @Override
    public SlimeChunk getChunk(final int x, final int z) {
        return null;
    }

    @Override
    public Collection<SlimeChunk> getChunkStorage() {
        return List.of();
    }

    @Override
    public ConcurrentMap<String, BinaryTag> getExtraData() {
        return new ConcurrentHashMap<>();
    }

    @Override
    public Collection<CompoundBinaryTag> getWorldMaps() {
        return List.of();
    }

    @Override
    public SlimePropertyMap getPropertyMap() {
        return this.propertyMap;
    }

    @Override
    public boolean isReadOnly() {
        return this.readOnly;
    }

    @Override
    public SlimeWorld clone(final String worldName) {
        return new FakeSlimeWorld(worldName, this.readOnly, this.propertyMap, this.loader);
    }

    @Override
    public SlimeWorld clone(final String worldName, final SlimeLoader targetLoader) {
        return new FakeSlimeWorld(worldName, this.readOnly, this.propertyMap, targetLoader);
    }

    @Override
    public int getDataVersion() {
        return 0;
    }

    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return FakeAdvancedSlimePaperApi.fakePersistentDataContainer();
    }
}
