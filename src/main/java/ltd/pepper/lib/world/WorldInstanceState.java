package ltd.pepper.lib.world;

/**
 * 实例生命周期状态（公共面收敛为五态；创建期占用等内部细节不对外）。
 *
 * <p>合法流转：{@code LOADING → ACTIVE → UNLOADING → UNLOADED}，
 * 任意加载/卸载失败 → {@code FAILED}（实例保留在 registry 供诊断）。</p>
 */
public enum WorldInstanceState {
    /** 正在创建（模板读取/世界注册中），此时 {@link WorldInstance#world()} 不可用。 */
    LOADING,
    /** 已就绪，可查询、可传送玩家、可修改方块（仅存在于本实例生命周期内）。 */
    ACTIVE,
    /** 卸载进行中，拒绝重复卸载与新业务操作。 */
    UNLOADING,
    /** 已卸载并移出 registry；句柄上的 {@code world()} 引用已失效。 */
    UNLOADED,
    /** 加载或卸载失败；实例记录保留用于诊断。 */
    FAILED,
}
