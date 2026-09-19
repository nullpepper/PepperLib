package ltd.pepper.lib.aswm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import ltd.pepper.lib.task.BukkitPepperScheduler;
import ltd.pepper.lib.world.UnloadOptions;
import ltd.pepper.lib.world.WorldInstance;
import ltd.pepper.lib.world.WorldInstanceRequest;
import ltd.pepper.lib.world.WorldInstanceState;
import ltd.pepper.lib.world.WorldProviderError;
import ltd.pepper.lib.world.WorldProviderException;
import ltd.pepper.lib.world.WorldTemplateRef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link BukkitMemoryWorldService} 生命周期测试（MockBukkit）：
 * 实例目录置于插件 tmp/worlds、卸载即删、Multiverse 无感知。
 */
class BukkitMemoryWorldServiceTest {

    private static final String TEMPLATE_ID = "arena-desert";

    private ServerMock server;
    private BukkitMemoryWorldService service;
    private Path templateDir;
    private Path worldContainer;

    @BeforeEach
    void setUp() throws IOException {
        this.server = MockBukkit.mock();
        this.worldContainer = Files.createTempDirectory("mv-worlds-");
        this.templateDir = Files.createTempDirectory("template-");
        Files.createDirectories(this.templateDir.resolve("region"));
        Files.writeString(this.templateDir.resolve("region").resolve("r.0.0.mca"), "fake-region");
        final FakeProviderPlugin plugin = MockBukkit.load(FakeProviderPlugin.class);
        this.service = new BukkitMemoryWorldService(plugin, new BukkitPepperScheduler(plugin)) {
            @Override
            protected Path worldContainerPath() {
                return worldContainer;
            }
        };
    }

    @AfterEach
    void tearDown() throws IOException {
        MockBukkit.unmock();
        deleteRecursively(this.templateDir);
        deleteRecursively(this.worldContainer);
    }

    @Test
    void createAndUnloadRemovesTmpDir() throws Exception {
        final WorldInstance instance = createAndAwait("match-1");
        assertNotNull(instance);
        assertEquals(WorldInstanceState.ACTIVE, instance.state());

        final String worldName = instance.worldName();
        assertNotNull(Bukkit.getWorld(worldName), "world should be registered");

        // 实例目录已创建于插件 tmp/worlds/<worldName>
        final Path tmpDir = this.service.tmpRoot().resolve(worldName);
        assertTrue(Files.exists(tmpDir), "instance tmp dir should exist");

        await(this.service.unload(instance.instanceId(), UnloadOptions.discardForShutdown()));

        assertNull(Bukkit.getWorld(worldName), "world removed after unload");
        assertTrue(!Files.exists(tmpDir), "instance tmp dir removed after unload");
    }

    @Test
    void findReturnsActiveOnly() throws Exception {
        createAndAwait("match-2");
        assertTrue(this.service.find("match-2").isPresent());
        assertEquals(1, this.service.instances().size());

        await(this.service.unload("match-2", UnloadOptions.discardForShutdown()));
        assertTrue(this.service.find("match-2").isEmpty());
    }

    /** 业务卸载契约：{@code discardWhenEmpty} 在世界仍有玩家时必须拒绝，且实例不被卸载。 */
    @Test
    void unloadWhenEmptyRefusesBusyInstance() throws Exception {
        final WorldInstance instance = createAndAwait("match-busy");
        final World world = Bukkit.getWorld(instance.worldName());
        assertNotNull(world, "world should be registered");
        final PlayerMock player = this.server.addPlayer("BusyPlayer");
        player.teleport(new Location(world, 0.5, 64, 0.5));
        assertFalse(world.getPlayers().isEmpty(), "player should be inside the instance world");

        final CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> await(this.service.unload(instance.instanceId(), UnloadOptions.discardWhenEmpty())));
        final WorldProviderException cause = assertInstanceOf(WorldProviderException.class, thrown.getCause());
        assertEquals(WorldProviderError.WORLD_NOT_EMPTY, cause.error());

        assertTrue(
                this.service.find(instance.instanceId()).isPresent(),
                "refused unload must keep the instance registered");
        assertNotNull(Bukkit.getWorld(instance.worldName()), "refused unload must not unload the world");
    }

    /**
     * 关服清理契约：{@code discardForShutdown} 不因玩家在场而被前置拒绝（区别于
     * {@code discardWhenEmpty}），但后端 {@code unloadWorld} 拒绝时不得假装已释放——
     * 报 {@link WorldProviderError#WORLD_UNLOAD_FAILED} 并保留实例。
     */
    @Test
    void unloadForShutdownReportsBackendFailureAndKeepsInstance() throws Exception {
        final WorldInstance instance = createAndAwait("match-shutdown");
        final World world = Bukkit.getWorld(instance.worldName());
        assertNotNull(world, "world should be registered");
        final PlayerMock player = this.server.addPlayer("ShutdownPlayer");
        player.teleport(new Location(world, 0.5, 64, 0.5));
        assertFalse(world.getPlayers().isEmpty(), "player should be inside the instance world");

        final CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> await(this.service.unload(instance.instanceId(), UnloadOptions.discardForShutdown())));
        final WorldProviderException cause = assertInstanceOf(WorldProviderException.class, thrown.getCause());
        assertEquals(
                WorldProviderError.WORLD_UNLOAD_FAILED,
                cause.error(),
                "backend refused the unload; the provider must not pretend the instance was released");

        assertTrue(
                this.service.find(instance.instanceId()).isPresent(),
                "failed unload must keep the instance registered");
        assertNotNull(Bukkit.getWorld(instance.worldName()), "failed unload must keep the world");
    }

    private WorldInstanceRequest request(final String instanceId) {
        return new WorldInstanceRequest(new WorldTemplateRef(TEMPLATE_ID, this.templateDir), instanceId);
    }

    private WorldInstance createAndAwait(final String instanceId) {
        return await(this.service.create(request(instanceId)));
    }

    /** 轮询驱动 MockBukkit tick 直到 future 完成（异步阶段在独立线程，须先等它
     *  把主线程任务排入队列，再 tick 驱动；真实 Paper 主线程持续 tick 无此问题）。 */
    private <T> T await(final CompletableFuture<T> future) {
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            this.server.getScheduler().performTicks(5);
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return future.join();
    }

    private static void deleteRecursively(final Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        final Path file, final java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ignored) {
        }
    }
}
