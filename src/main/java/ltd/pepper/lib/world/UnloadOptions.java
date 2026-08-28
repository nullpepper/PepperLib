package ltd.pepper.lib.world;

/**
 * 实例卸载选项。
 *
 * <p>两种语义工厂：
 * <ul>
 *   <li>{@link #discardWhenEmpty()}：业务卸载（比赛/副本结束 → 清场 → 卸载）；
 *       世界仍有玩家时拒绝（{@link WorldProviderError#WORLD_NOT_EMPTY}）；</li>
 *   <li>{@link #discardForShutdown()}：服务器关闭清理；不因玩家在场而放弃，
 *       由 provider 记录失败实例。</li>
 * </ul>
 *
 * <p>v1 provider 一律丢弃实例修改（{@code save=false}）；传入 {@code save=true}
 * 将得到 {@link WorldProviderError#INVALID_REQUEST}——实例世界不支持持久化。</p>
 *
 * @param save         是否保存实例修改（v1 恒为 false）
 * @param requireEmpty 卸载前是否要求世界为空
 */
public record UnloadOptions(boolean save, boolean requireEmpty) {

    /** 业务卸载：不保存 + 要求世界为空。 */
    public static UnloadOptions discardWhenEmpty() {
        return new UnloadOptions(false, true);
    }

    /** 关闭清理：不保存 + 强制卸载（失败记录，不中断清理流程）。 */
    public static UnloadOptions discardForShutdown() {
        return new UnloadOptions(false, false);
    }
}
