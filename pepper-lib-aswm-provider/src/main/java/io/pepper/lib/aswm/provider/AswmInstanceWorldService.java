package io.pepper.lib.aswm.provider;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import io.pepper.lib.task.BukkitPepperScheduler;
import io.pepper.lib.world.InstanceWorldService;
import io.pepper.lib.world.UnloadOptions;
import io.pepper.lib.world.WorldInstance;
import io.pepper.lib.world.WorldInstanceRequest;
import io.pepper.lib.world.WorldInstanceState;
import io.pepper.lib.world.WorldProviderError;
import io.pepper.lib.world.WorldProviderException;
import io.pepper.lib.world.WorldProviderInfo;
import io.pepper.lib.world.WorldTemplateRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link InstanceWorldService} 的 ASP 实现（Advanced Slime Paper API 3.0.0）。
 *
 * <p>创建流程：模板文件读取/解析在异步线程（{@code readWorld}），世界注册在主线程
 * （{@code clone + loadWorld}）；模板经 {@code SlimeWorld} 对象缓存（内存一份，
 * 每实例 {@code clone} 出独立活体世界）。实例修改仅存在于实例生命周期内，
 * 卸载即丢弃（{@code readOnly=true} + {@code unloadWorld(save=false)} + autoSave 关闭，
 * 三重防落盘）。</p>
 *
 * <p>线程模型：registry 并发安全；主线程操作经 {@link BukkitPepperScheduler}；
 * 服务器关闭（{@code close()}）同步执行（onDisable 期间调度器不可用）。</p>
 */
final class AswmInstanceWorldService implements InstanceWorldService {

    private static final String WORLD_NAME_PREFIX = "pepper_inst_";

    private final JavaPlugin plugin;
    private final AdvancedSlimePaperAPI api;
    private final BukkitPepperScheduler scheduler;
    private final ConcurrentMap<String, Entry> registry = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SlimeWorld> templateCache = new ConcurrentHashMap<>();
    private volatile boolean closed;

    AswmInstanceWorldService(
            final JavaPlugin plugin, final AdvancedSlimePaperAPI api, final BukkitPepperScheduler scheduler) {
        this.plugin = plugin;
        this.api = api;
        this.scheduler = scheduler;
    }

    @Override
    public WorldProviderInfo providerInfo() {
        return new WorldProviderInfo(
                "pepper-lib-aswm",
                this.plugin.getDescription().getVersion(),
                "Paper 26.1.2（本地构建 fork vjh0107；包重命名 asp.api）",
                "com.infernalsuite.asp:api:4.2.0-SNAPSHOT");
    }

    @Override
    public CompletableFuture<WorldInstance> create(final WorldInstanceRequest request) {
        if (this.closed) {
            return failed(WorldProviderError.SERVICE_CLOSED, "service is closed");
        }
        if (request == null) {
            return failed(WorldProviderError.INVALID_REQUEST, "request must not be null");
        }
        final String instanceId = request.instanceId();
        final String worldName =
                WORLD_NAME_PREFIX + UUID.randomUUID().toString().replace("-", "");
        final Entry entry = new Entry(
                new AswmWorldInstance(instanceId, worldName, request.template().id()));
        if (this.registry.putIfAbsent(instanceId, entry) != null) {
            return failed(WorldProviderError.INSTANCE_ID_CONFLICT, "instance id already in use: " + instanceId);
        }
        final WorldTemplateRef template = request.template();
        final CompletableFuture<WorldInstance> future = CompletableFuture.supplyAsync(
                        () -> this.readTemplate(template), task -> this.scheduler.runAsync(task))
                .thenCompose(slimeWorld -> this.scheduler.supplyOnMain(() -> this.loadOnMain(entry, slimeWorld)));
        future.whenComplete((result, error) -> {
            if (error != null) {
                this.cleanup(entry);
            }
        });
        return future;
    }

    @Override
    public Optional<WorldInstance> find(final String instanceId) {
        if (instanceId == null) {
            return Optional.empty();
        }
        final Entry entry = this.registry.get(instanceId);
        return entry != null && entry.instance().state() == WorldInstanceState.ACTIVE
                ? Optional.of(entry.instance())
                : Optional.empty();
    }

    @Override
    public Collection<WorldInstance> instances() {
        return this.registry.values().stream()
                .map(Entry::instance)
                .filter(instance -> instance.state() == WorldInstanceState.ACTIVE)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public CompletableFuture<Void> unload(final String instanceId, final UnloadOptions options) {
        if (this.closed) {
            return failedVoid(WorldProviderError.SERVICE_CLOSED, "service is closed");
        }
        return this.unloadInternal(instanceId, options);
    }

    @Override
    public CompletableFuture<Void> unloadAll(final UnloadOptions options) {
        final List<Entry> snapshot = new ArrayList<>(this.registry.values());
        final List<CompletableFuture<Void>> futures = new ArrayList<>(snapshot.size());
        for (final Entry entry : snapshot) {
            futures.add(this.unloadInternal(entry.instance().instanceId(), options));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .handle((result, error) -> {
                    // 逐实例失败记录而不中断整体流程（关闭清理语义）。
                    for (final CompletableFuture<Void> future : futures) {
                        try {
                            future.join();
                        } catch (final CompletionException e) {
                            this.plugin
                                    .getLogger()
                                    .warning("unloadAll: instance unload failed: "
                                            + e.getCause().getMessage());
                        }
                    }
                    return null;
                });
    }

    /** 关闭：置 closed + 同步卸载全部实例（须在主线程调用，onDisable 场景）。 */
    void close() {
        this.closed = true;
        for (final Entry entry : List.copyOf(this.registry.values())) {
            final AswmWorldInstance instance = entry.instance();
            try {
                final World world = Bukkit.getWorld(instance.worldName());
                if (world != null) {
                    instance.markUnloading();
                    if (!Bukkit.unloadWorld(world, false)) {
                        this.plugin
                                .getLogger()
                                .warning("close: unloadWorld returned false for " + instance.worldName());
                    }
                    instance.markUnloaded();
                }
            } catch (final RuntimeException e) {
                instance.markFailed();
                this.plugin
                        .getLogger()
                        .warning("close: failed to unload " + instance.worldName() + ": " + e.getMessage());
            } finally {
                this.registry.remove(instance.instanceId());
            }
        }
        this.templateCache.clear();
    }

    private CompletableFuture<Void> unloadInternal(final String instanceId, final UnloadOptions options) {
        if (instanceId == null || options == null) {
            return failedVoid(WorldProviderError.INVALID_REQUEST, "instanceId and options must not be null");
        }
        if (options.save()) {
            return failedVoid(
                    WorldProviderError.INVALID_REQUEST, "instance saving is not supported; use save=false (discard)");
        }
        final Entry entry = this.registry.get(instanceId);
        if (entry == null) {
            // 未知 id 视为已卸载：业务侧重复清理（比赛结束回调竞态）幂等成功。
            return CompletableFuture.completedFuture(null);
        }
        final AswmWorldInstance instance = entry.instance();
        return this.scheduler.supplyOnMain(() -> {
            final World world = Bukkit.getWorld(instance.worldName());
            if (world == null) {
                this.registry.remove(instanceId, entry);
                instance.markUnloaded();
                return null;
            }
            if (options.requireEmpty() && !world.getPlayers().isEmpty()) {
                throw new WorldProviderException(
                        WorldProviderError.WORLD_NOT_EMPTY,
                        "world still has players: " + instance.worldName() + " ("
                                + world.getPlayers().size() + ")");
            }
            instance.markUnloading();
            if (!Bukkit.unloadWorld(world, false)) {
                instance.markFailed();
                throw new WorldProviderException(
                        WorldProviderError.WORLD_UNLOAD_FAILED,
                        "unloadWorld returned false for " + instance.worldName());
            }
            this.registry.remove(instanceId, entry);
            instance.markUnloaded();
            return null;
        });
    }

    /** 异步线程：模板文件校验 + 读取/解析（经缓存复用，内存一份）。 */
    private SlimeWorld readTemplate(final WorldTemplateRef template) {
        final Path file = template.source();
        if (!Files.isRegularFile(file)) {
            throw new WorldProviderException(WorldProviderError.TEMPLATE_ERROR, "template file not found: " + file);
        }
        if (!Files.isReadable(file)) {
            throw new WorldProviderException(WorldProviderError.TEMPLATE_ERROR, "template file not readable: " + file);
        }
        final SlimeWorld cached = this.templateCache.get(template.id());
        if (cached != null) {
            return cached;
        }
        final SlimeLoader loader = new TemplateFileLoader(template.id(), file);
        try {
            final SlimeWorld world = this.api.readWorld(loader, template.id(), true, new SlimePropertyMap());
            this.templateCache.put(template.id(), world);
            return world;
        } catch (final UnknownWorldException | CorruptedWorldException | NewerFormatException | IOException e) {
            throw new WorldProviderException(
                    WorldProviderError.TEMPLATE_ERROR,
                    "failed to read template '" + template.id() + "': " + e.getMessage(),
                    e);
        }
    }

    /** 主线程：克隆模板 → ASP 注册活体世界 → 关闭自动保存 → ACTIVE。 */
    private WorldInstance loadOnMain(final Entry entry, final SlimeWorld template) {
        final AswmWorldInstance instance = entry.instance();
        try {
            final SlimeWorld instanceWorld = template.clone(instance.worldName());
            this.api.loadWorld(instanceWorld, true);
            final World world = Bukkit.getWorld(instance.worldName());
            if (world == null) {
                throw new WorldProviderException(
                        WorldProviderError.WORLD_LOAD_FAILED,
                        "world not registered after load: " + instance.worldName());
            }
            world.setAutoSave(false);
            instance.activate(world);
            return instance;
        } catch (final WorldProviderException e) {
            throw e;
        } catch (final IllegalArgumentException e) {
            throw new WorldProviderException(
                    WorldProviderError.WORLD_LOAD_FAILED,
                    "failed to load world '" + instance.worldName() + "': " + e.getMessage(),
                    e);
        }
    }

    /** 创建失败清理：移除 registry 记录 + 尽力卸载已注册的半成品世界（须主线程）。 */
    private void cleanup(final Entry entry) {
        this.registry.remove(entry.instance().instanceId());
        entry.instance().markFailed();
        final String worldName = entry.instance().worldName();
        final Runnable unload = () -> {
            final World world = Bukkit.getWorld(worldName);
            if (world != null) {
                try {
                    Bukkit.unloadWorld(world, false);
                } catch (final RuntimeException ignored) {
                    // 尽力而为：失败留给日志诊断，不掩盖原始创建错误。
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            unload.run();
        } else {
            try {
                this.scheduler.runTask(unload);
            } catch (final RuntimeException ignored) {
                // 服务关闭竞态下调度不可用：放弃清理（世界卸载由关闭流程兜底）。
            }
        }
    }

    private static <T> CompletableFuture<T> failed(final WorldProviderError error, final String message) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new WorldProviderException(error, message));
        return future;
    }

    private static CompletableFuture<Void> failedVoid(final WorldProviderError error, final String message) {
        return failed(error, message);
    }

    /** registry 条目：持有实例句柄（状态机封装在句柄内）。 */
    private static final class Entry {

        private final AswmWorldInstance instance;

        Entry(final AswmWorldInstance instance) {
            this.instance = instance;
        }

        AswmWorldInstance instance() {
            return this.instance;
        }
    }
}
