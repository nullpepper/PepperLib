package io.pepper.lib.gui;

import io.pepper.lib.validation.Preconditions;
import java.util.UUID;

/**
 * GUI 会话标识：玩家 + 打开实例 + 版本。
 *
 * <p>异步回调回到主线程后必须用本标识验证会话仍然有效，禁止旧页面覆盖新页面
 * （内部设计文档 extraction-plan §5.2）。</p>
 *
 * <p><b>version 为预留字段</b>：当前生产流程恒为 0（{@link GuiHost#openPage} 固定传入），
 * 会话失效由 {@link PageGuiContext#invalidate()}（页面关闭时将 session 置 null）管理。
 * 保留该组件仅为兼容存量三参构造调用（两插件 {@code new GuiSessionId(uuid, uuid, 0L)}），
 * 不得依赖其递增语义；后续如需版本化会话可在此实现。</p>
 */
public record GuiSessionId(UUID playerId, UUID instanceId, long version) {

    /**
     * 构造会话标识。
     *
     * @param playerId 玩家 UUID
     * @param instanceId 打开实例 UUID（每次打开新生成）
     * @param version 预留版本号（当前恒为 0，勿依赖递增语义）
     */
    public GuiSessionId {
        Preconditions.requireNonNull(playerId, "playerId");
        Preconditions.requireNonNull(instanceId, "instanceId");
    }
}
