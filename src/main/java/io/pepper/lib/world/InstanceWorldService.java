package io.pepper.lib.world;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/**
 * 实例世界服务：与具体 Slime 实现解耦的公共 SPI（{@code Experimental}，
 * 双消费者共设计——PVP/PVE 竞技场插件；provider 经 Bukkit ServicesManager 注册，
 * 经 {@code Bukkit.getServicesManager().load(InstanceWorldService.class)} 获取）。
 *
 * <p><b>能力声明</b>：本类型标注 {@link io.pepper.lib.runtime.MinMinecraftVersion
 * @MinMinecraftVersion}（能力 {@code world-instance}，最低版本 1.18）；消费者先经
 * {@code PepperLibRuntime.supports("world-instance")} 确认库内 API 存在，再经
 * ServicesManager 探测 provider 是否注册——两者是不同层：API 存在 ≠ provider 可用。</p>
 *
 * <p><b>异步契约</b>：{@code create}/{@code unload} 返回 {@link CompletableFuture}；
 * 模板文件读取/解析在异步线程，Bukkit 世界注册/卸载在主线程；future 完成回调
 * 不保证在主线程，调用方触碰 {@link World} 前须自行切回主线程（可复用
 * {@code io.pepper.lib.task.PepperScheduler}）。</p>
 *
 * <p><b>失败契约</b>：失败经 {@link WorldProviderException} 携带稳定错误码
 * {@link WorldProviderError} 表达；provider 缺失时 {@code create} 以
 * {@code PROVIDER_UNAVAILABLE} 完成异常，绝不降级为普通 Bukkit 磁盘世界。</p>
 *
 * <p><b>隔离契约</b>：每个实例是独立活体世界（独立区块/实体/容器状态）；实例修改
 * 仅存在于实例生命周期内，卸载即丢弃，永不写回模板。</p>
 *
 * <p><b>部署契约（前置模式）</b>：本服务跨插件互通依赖服务器单一实例的
 * {@code io.pepper.lib.*} 类（PepperLib 前置插件）；shade 模式消费者因类重定位，
 * 与服务器注册的 {@code InstanceWorldService} 是不同类型，无法经 ServicesManager
 * 互通——shade 消费者须自带 provider 实现。</p>
 */
@io.pepper.lib.runtime.MinMinecraftVersion(value = "1.18", capability = "world-instance")
public interface InstanceWorldService {

    /** @return provider 诊断信息（id/版本/支持范围） */
    WorldProviderInfo providerInfo();

    /**
     * 创建世界实例：模板读取与解析异步执行，Bukkit 世界注册在主线程。
     *
     * @param request 模板引用 + 业务实例 id（非 null）
     * @return 实例句柄；失败以 {@link WorldProviderException} 完成异常
     */
    CompletableFuture<WorldInstance> create(WorldInstanceRequest request);

    /**
     * @param instanceId 业务实例 id
     * @return 该 id 的 {@link WorldInstanceState#ACTIVE} 实例；不存在或非 ACTIVE 为空
     */
    Optional<WorldInstance> find(String instanceId);

    /** @return 当前全部 ACTIVE 实例的不可变快照 */
    Collection<WorldInstance> instances();

    /**
     * 卸载实例（主线程执行）。
     *
     * @param instanceId 业务实例 id；未知 id 视为已卸载（幂等成功）
     * @param options    卸载选项（见 {@link UnloadOptions}）
     * @return 卸载完成；失败以 {@link WorldProviderException} 完成异常且实例保留
     */
    CompletableFuture<Void> unload(String instanceId, UnloadOptions options);

    /**
     * 卸载全部实例（按创建序逆序）；逐实例失败被记录而不中断整体流程——
     * 用于服务器关闭清理（配合 {@link UnloadOptions#discardForShutdown()}）。
     *
     * @param options 卸载选项
     * @return 全部尝试结束后完成（不因个别失败而异常完成）
     */
    CompletableFuture<Void> unloadAll(UnloadOptions options);
}
