package io.pepper.lib.aswm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pepper.lib.task.BukkitPepperScheduler;
import io.pepper.lib.world.InstanceWorldService;
import io.pepper.lib.world.UnloadOptions;
import io.pepper.lib.world.WorldInstance;
import io.pepper.lib.world.WorldInstanceState;
import io.pepper.lib.world.WorldInstanceRequest;
import io.pepper.lib.world.WorldTemplateRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
                public java.nio.file.FileVisitResult visitFile(final Path file, final java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ignored) {
        }
    }
}
