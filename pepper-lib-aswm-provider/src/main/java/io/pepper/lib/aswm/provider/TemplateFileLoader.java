package io.pepper.lib.aswm.provider;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单文件只读 {@link SlimeLoader}：把竞技场模板 Slime 文件当作唯一世界源。
 *
 * <p>{@code worldName} 恒等于模板 {@code id}；{@code saveWorld}/{@code deleteWorld}
 * 抛 {@link UnsupportedOperationException}——模板是只读源数据，实例修改永不写回。</p>
 */
final class TemplateFileLoader implements SlimeLoader {

    private final String templateId;
    private final Path file;

    TemplateFileLoader(final String templateId, final Path file) {
        this.templateId = templateId;
        this.file = file;
    }

    @Override
    public byte[] readWorld(final String worldName) throws UnknownWorldException, IOException {
        if (!this.templateId.equals(worldName)) {
            throw new UnknownWorldException(
                    "template id mismatch: expected '" + this.templateId + "', got '" + worldName + "'");
        }
        if (!Files.isRegularFile(this.file)) {
            throw new UnknownWorldException("template file not found: " + this.file);
        }
        return Files.readAllBytes(this.file);
    }

    @Override
    public boolean worldExists(final String worldName) throws IOException {
        return this.templateId.equals(worldName) && Files.isRegularFile(this.file);
    }

    @Override
    public List<String> listWorlds() throws IOException {
        return Files.isRegularFile(this.file) ? List.of(this.templateId) : List.of();
    }

    @Override
    public void saveWorld(final String worldName, final byte[] serializedWorld) throws IOException {
        throw new UnsupportedOperationException("template loader is read-only: " + this.file);
    }

    @Override
    public void deleteWorld(final String worldName) throws UnknownWorldException, IOException {
        throw new UnsupportedOperationException("template loader is read-only: " + this.file);
    }
}
