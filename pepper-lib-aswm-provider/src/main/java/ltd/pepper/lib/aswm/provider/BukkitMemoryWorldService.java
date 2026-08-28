package ltd.pepper.lib.aswm.provider;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import ltd.pepper.lib.task.BukkitPepperScheduler;
import ltd.pepper.lib.world.InstanceWorldService;
import ltd.pepper.lib.world.UnloadOptions;
import ltd.pepper.lib.world.WorldInstance;
import ltd.pepper.lib.world.WorldInstanceRequest;
import ltd.pepper.lib.world.WorldInstanceState;
import ltd.pepper.lib.world.WorldProviderError;
import ltd.pepper.lib.world.WorldProviderException;
import ltd.pepper.lib.world.WorldProviderInfo;
import ltd.pepper.lib.world.WorldTemplateRef;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 纯内存语义的 Bukkit 后备实现：实例世界文件夹置于插件 {@code tmp/worlds/}，
 * 通过符号链接暴露到服务端世界容器，实现：
 * <ul>
 *   <li>Multiverse 零感知（世界不在 {@code worlds.yml}，/mv list 不出现）</li>
 *   <li>模板只读（每实例从模板目录递归复制）</li>
 *   <li>卸载即删（unload + 删除插件 tmp 下的实例目录 + 链接）</li>
 * </ul>
 * <p>Fallback 链：ASP 可用时由 {@link AswmInstanceWorldService} 提供服务；
 * ASP 不可用时由本实现兜底，保证 Purpur 等非 ASP 核心上仍可开赛。</p>
 */
class BukkitMemoryWorldService implements InstanceWorldService {

    private static final String WORLD_NAME_PREFIX = "pepper_inst_";

    private final JavaPlugin plugin;
    private final BukkitPepperScheduler scheduler;
    private final Path tmpRoot;
    private final ConcurrentMap<String, Entry> registry = new ConcurrentHashMap<>();
    private volatile boolean closed;

    BukkitMemoryWorldService(final JavaPlugin plugin, final BukkitPepperScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.tmpRoot = plugin.getDataFolder().toPath().resolve("tmp").resolve("worlds");
        try {
            Files.createDirectories(this.tmpRoot);
        } catch (final IOException e) {
            plugin.getLogger().warning("无法创建实例世界临时目录 " + this.tmpRoot + ": " + e.getMessage());
        }
    }

    /** 仅供测试访问实例临时根目录。 */
    Path tmpRoot() {
        return this.tmpRoot;
    }

    /** 世界容器路径（Bukkit 默认取 {@code Bukkit.getWorldContainer()}；测试可覆写）。 */
    protected Path worldContainerPath() {
        return Bukkit.getWorldContainer().toPath();
    }

    @Override
    public WorldProviderInfo providerInfo() {
        return new WorldProviderInfo(
                "pepper-lib-bukkit-memory",
                this.plugin.getDescription().getVersion(),
                "Paper/Purpur 纯 Bukkit 内存语义（插件 tmp/worlds + 符号链接隔离 Multiverse）",
                "bukkit:memory-tmp");
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
        final Entry entry = new Entry(new BukkitWorldInstance(
                instanceId, worldName, request.template().id()));
        if (this.registry.putIfAbsent(instanceId, entry) != null) {
            return failed(WorldProviderError.INSTANCE_ID_CONFLICT, "instance id already in use: " + instanceId);
        }
        final WorldTemplateRef template = request.template();
        final CompletableFuture<WorldInstance> future = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                prepareInstanceDir(template, worldName);
                            } catch (final IOException e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                            return worldName;
                        },
                        task -> this.scheduler.runAsync(task))
                .thenCompose(name -> this.scheduler.supplyOnMain(() -> loadOnMain(entry, name)));
        future.whenComplete((result, error) -> {
            if (error != null) {
                cleanup(entry);
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
                .filter(i -> i.state() == WorldInstanceState.ACTIVE)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public CompletableFuture<Void> unload(final String instanceId, final UnloadOptions options) {
        if (this.closed) {
            return failedVoid(WorldProviderError.SERVICE_CLOSED, "service is closed");
        }
        return unloadInternal(instanceId, options);
    }

    @Override
    public CompletableFuture<Void> unloadAll(final UnloadOptions options) {
        final List<Entry> snapshot = new ArrayList<>(this.registry.values());
        final List<CompletableFuture<Void>> futures = new ArrayList<>(snapshot.size());
        for (final Entry entry : snapshot) {
            futures.add(unloadInternal(entry.instance().instanceId(), options));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    void close() {
        this.closed = true;
        // onDisable 期间调度器不可用，同步清理
        final List<Entry> snapshot = new ArrayList<>(this.registry.values());
        for (final Entry entry : snapshot) {
            try {
                unloadSync(entry.instance().instanceId());
            } catch (final Exception ignored) {
            }
        }
        // 清理残留的 tmp 实例目录（防御性）
        try {
            if (Files.isDirectory(this.tmpRoot)) {
                try (var stream = Files.list(this.tmpRoot)) {
                    stream.forEach(p -> deleteRecursively(p));
                }
            }
        } catch (final IOException ignored) {
        }
    }

    private void prepareInstanceDir(final WorldTemplateRef template, final String worldName) throws IOException {
        final Path source = template.source();
        if (!Files.exists(source)) {
            throw new IOException("template source not found: " + source);
        }
        final Path tmpDir = this.tmpRoot.resolve(worldName);
        // 防御：只允许在插件 tmp/worlds 下创建
        if (!tmpDir.normalize().startsWith(this.tmpRoot.normalize())) {
            throw new IOException("illegal instance dir: " + tmpDir);
        }
        if (Files.exists(tmpDir)) {
            deleteRecursively(tmpDir);
        }
        Files.createDirectories(tmpDir.getParent());
        if (Files.isDirectory(source)) {
            copyDirectory(source, tmpDir);
        } else {
            // 单文件模板（兼容旧 slime 场景）：直接复制为 level 占位，世界创建后由调用方填充
            Files.createDirectories(tmpDir);
            Files.copy(source, tmpDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        // 在服务端世界容器创建符号链接 -> 插件 tmp 实际目录
        final Path link = worldContainerPath().resolve(worldName);
        try {
            if (Files.exists(link) || Files.isSymbolicLink(link)) {
                Files.deleteIfExists(link);
            }
            Files.createSymbolicLink(link, tmpDir);
        } catch (final IOException | UnsupportedOperationException e) {
            // Windows 或无 symlink 权限：回退为直接复制到世界容器
            plugin.getLogger().warning("符号链接创建失败，回退为直接复制到世界容器: " + e.getMessage());
            final Path worldContainerDir = worldContainerPath().resolve(worldName);
            if (!Files.exists(worldContainerDir)) {
                copyDirectory(tmpDir, worldContainerDir);
            }
        }
    }

    private WorldInstance loadOnMain(final Entry entry, final String worldName) {
        if (Bukkit.getWorld(worldName) != null) {
            throw new java.util.concurrent.CompletionException(new WorldProviderException(
                    WorldProviderError.WORLD_LOAD_FAILED, "world already exists: " + worldName));
        }
        final World world = Bukkit.createWorld(new WorldCreator(worldName));
        if (world == null) {
            throw new java.util.concurrent.CompletionException(new WorldProviderException(
                    WorldProviderError.WORLD_LOAD_FAILED, "Bukkit.createWorld returned null: " + worldName));
        }
        world.setAutoSave(false);
        entry.instance().activate(world);
        return entry.instance();
    }

    private CompletableFuture<Void> unloadInternal(final String instanceId, final UnloadOptions options) {
        final Entry entry = this.registry.get(instanceId);
        if (entry == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.scheduler.supplyOnMain(() -> {
            unloadSync(instanceId);
            return null;
        });
    }

    private void unloadSync(final String instanceId) {
        final Entry entry = this.registry.remove(instanceId);
        if (entry == null) {
            return;
        }
        entry.instance().markUnloading();
        final String worldName = entry.instance().worldName();
        final World world = Bukkit.getWorld(worldName);
        if (world != null) {
            try {
                Bukkit.unloadWorld(world, false);
            } catch (final Exception ignored) {
            }
        }
        // 删除世界容器中的链接/目录
        final Path link = worldContainerPath().resolve(worldName);
        try {
            if (Files.isSymbolicLink(link)) {
                Files.deleteIfExists(link);
            } else if (Files.isDirectory(link)) {
                deleteRecursively(link);
            }
        } catch (final IOException ignored) {
        }
        // 删除插件 tmp 下的实际数据
        final Path tmpDir = this.tmpRoot.resolve(worldName);
        deleteRecursively(tmpDir);
        // 防御：若 Multiverse 已将该世界写入 worlds.yml，尝试清理（可选，不强依赖 MV API）
        try {
            // 通过反射避免编译期依赖 Multiverse
            final Class<?> mvCoreClass = Class.forName("com.onarandombox.MultiverseCore.MultiverseCore");
            final Object mvCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mvCore != null) {
                // 仅日志提示，不主动改 worlds.yml（MV 的 deleteWorld 需要主线程且可能抛异常）
                plugin.getLogger().fine("检测到 Multiverse-Core，实例世界 " + worldName + " 已从 Bukkit 卸载，MV 未注册则无需清理");
            }
        } catch (final ClassNotFoundException ignored) {
        }
        entry.instance().markUnloaded();
    }

    private void cleanup(final Entry entry) {
        this.registry.remove(entry.instance().instanceId());
        entry.instance().markFailed();
        final String worldName = entry.instance().worldName();
        final Path link = worldContainerPath().resolve(worldName);
        final Path tmpDir = this.tmpRoot.resolve(worldName);
        final Runnable task = () -> {
            final World world = Bukkit.getWorld(worldName);
            if (world != null) {
                try {
                    Bukkit.unloadWorld(world, false);
                } catch (final Exception ignored) {
                }
            }
            try {
                if (Files.isSymbolicLink(link)) {
                    Files.deleteIfExists(link);
                } else if (Files.isDirectory(link)) {
                    deleteRecursively(link);
                }
            } catch (final IOException ignored) {
            }
            deleteRecursively(tmpDir);
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            try {
                this.scheduler.runTask(task);
            } catch (final Exception ignored) {
            }
        }
    }

    private static void copyDirectory(final Path source, final Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
                    throws IOException {
                final Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final Path rel = source.relativize(file);
                Files.copy(file, target.resolve(rel.toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(final Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ignored) {
        }
    }

    private static <T> CompletableFuture<T> failed(final WorldProviderError error, final String message) {
        final CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(new WorldProviderException(error, message));
        return f;
    }

    private static CompletableFuture<Void> failedVoid(final WorldProviderError error, final String message) {
        return failed(error, message);
    }

    private static final class Entry {
        private final BukkitWorldInstance instance;

        Entry(final BukkitWorldInstance instance) {
            this.instance = instance;
        }

        BukkitWorldInstance instance() {
            return this.instance;
        }
    }
}
