package io.pepper.lib.world;

import java.util.Objects;

/**
 * 实例创建请求：模板引用 + 业务侧实例 id（如比赛 UUID）。
 *
 * <p>{@code instanceId} 是竞技场业务标识，同一 id 不得并发创建两个实例
 * （冲突 → {@link WorldProviderError#INSTANCE_ID_CONFLICT}）；Bukkit 世界名
 * 由 provider 生成并保证唯一。</p>
 */
public record WorldInstanceRequest(WorldTemplateRef template, String instanceId) {

    public WorldInstanceRequest {
        Objects.requireNonNull(template, "template");
        WorldIdRules.requireValidId(instanceId, "instanceId");
    }
}
