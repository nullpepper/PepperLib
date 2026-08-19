package io.pepper.lib.confirm;

import io.pepper.lib.validation.Preconditions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家退出服务器时清除其待确认的操作，避免过期的确认记录长期占用内存。
 *
 * <p>统一两插件实现（两边的 {@code ConfirmCleanupListener} 逻辑逐字相同）。
 * 构造注入 registry（弃用 PepperClaim 的单例形态）。</p>
 */
public final class ConfirmCleanupListener implements Listener {

    private final ConfirmRegistry<?> confirmRegistry;

    public ConfirmCleanupListener(final ConfirmRegistry<?> confirmRegistry) {
        Preconditions.requireNonNull(confirmRegistry, "confirmRegistry");
        this.confirmRegistry = confirmRegistry;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.confirmRegistry.clear(event.getPlayer().getUniqueId());
    }
}
