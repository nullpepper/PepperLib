package io.pepper.lib.world;

/**
 * 实例世界 provider 的稳定错误码（公共契约面，调用方按码分支，不解析异常文本）。
 *
 * <p>错误码集合的增删改必须走 PepperLib API 变更流程（见 {@code WorldProviderExceptionTest}
 * 的数量守卫）；provider 内部诊断细节进日志与 {@link WorldProviderInfo}，不进错误码。</p>
 */
public enum WorldProviderError {

    /** 没有可用 provider（未安装/环境不兼容），创建请求不得降级为普通 Bukkit 世界。 */
    PROVIDER_UNAVAILABLE,

    /** 请求非法：null、非法 id、不支持的选项组合（如 save=true）。 */
    INVALID_REQUEST,

    /** 模板文件缺失、不可读或格式损坏（含 Slime 格式版本过高）。 */
    TEMPLATE_ERROR,

    /** 业务实例 id 已被占用，拒绝创建并保留既有实例。 */
    INSTANCE_ID_CONFLICT,

    /** 后端世界加载失败（含世界名冲突重试后仍失败、注册后取不到 World）。 */
    WORLD_LOAD_FAILED,

    /** 卸载要求世界为空但仍有玩家在场（业务卸载语义 {@link UnloadOptions#requireEmpty()}）。 */
    WORLD_NOT_EMPTY,

    /** 后端卸载失败（unloadWorld 返回 false 等），实例保留、不得假装已释放。 */
    WORLD_UNLOAD_FAILED,

    /** 服务已关闭（服务器关闭流程中或之后），不再接受创建/卸载请求。 */
    SERVICE_CLOSED,
}
