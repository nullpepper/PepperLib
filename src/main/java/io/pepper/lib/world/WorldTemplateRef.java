package io.pepper.lib.world;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 竞技场模板引用：{@code id} 为业务侧模板标识（日志/缓存/诊断用），
 * {@code source} 为 Slime 模板文件的绝对路径（调用方各自插件数据目录）。
 *
 * <p>约束：
 * <ul>
 *   <li>{@code id} 只允许 {@code [a-z0-9_-]+}（与 Bukkit 世界名命名空间一致的保守子集）；</li>
 *   <li>{@code source} 必须为绝对路径；存在性/可读性由 provider 在创建时校验
 *       （缺失 → {@link WorldProviderError#TEMPLATE_ERROR}）；</li>
 *   <li>模板是只读源数据：实例修改永不写回 {@code source}；</li>
 *   <li>实例的 Bukkit 世界名由 provider 生成，调用方不得经模板文件名控制世界名。</li>
 * </ul>
 */
public record WorldTemplateRef(String id, Path source) {

    public WorldTemplateRef {
        WorldIdRules.requireValidId(id, "id");
        Objects.requireNonNull(source, "source");
        if (!source.isAbsolute()) {
            throw new IllegalArgumentException("source must be an absolute path: " + source);
        }
    }
}
