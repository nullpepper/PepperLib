package io.pepper.lib.gui;

import io.pepper.lib.validation.Preconditions;
import java.util.UUID;

/**
 * GUI 会话标识：玩家 + 打开实例 + 版本。
 *
 * <p>异步回调回到主线程后必须用本标识验证会话仍然有效，禁止旧页面覆盖新页面
 * （PepperLib-Extraction-Plan §5.2）。</p>
 */
public record GuiSessionId(UUID playerId, UUID instanceId, long version) {

    /**
     * 构造会话标识。
     *
     * @param playerId 玩家 UUID
     * @param instanceId 打开实例 UUID（每次打开新生成）
     * @param version 版本号（同实例内递增）
     */
    public GuiSessionId {
        Preconditions.requireNonNull(playerId, "playerId");
        Preconditions.requireNonNull(instanceId, "instanceId");
    }
}
