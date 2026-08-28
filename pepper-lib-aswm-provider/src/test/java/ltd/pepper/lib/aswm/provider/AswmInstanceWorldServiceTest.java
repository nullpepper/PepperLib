package ltd.pepper.lib.aswm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ltd.pepper.lib.world.InstanceWorldService;
import ltd.pepper.lib.world.UnloadOptions;
import ltd.pepper.lib.world.WorldInstance;
import ltd.pepper.lib.world.WorldInstanceRequest;
import ltd.pepper.lib.world.WorldInstanceState;
import ltd.pepper.lib.world.WorldProviderError;
import ltd.pepper.lib.world.WorldProviderException;
import ltd.pepper.lib.world.WorldTemplateRef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link AswmInstanceWorldService} 生命周期行为测试（MockBukkit + 假 ASP API）：
 * 创建/查询/卸载/冲突/失败清理/关闭语义。
 */
class AswmInstanceWorldServiceTest {

    private static final String TEMPLATE_ID = "arena-desert";

    private ServerMock server;
    private AswmInstanceWorldService service;
    private Path templateFile;

    @BeforeEach
    void setUp() throws IOException {
        this.server = MockBukkit.mock();
        this.templateFile = Files.createTempFile("template-", ".slime");
        Files.writeString(this.templateFile, "fake-slime-bytes");
        this.service = new AswmInstanceWorldService(
                MockBukkit.load(FakeProviderPlugin.class),
                new FakeAdvancedSlimePaperApi(),
                new BukkitPepperScheduler(MockBukkit.load(FakeProviderPlugin.class)));
    }

    @AfterEach
    void tearDown() throws IOException {
        MockBukkit.unmock();
        Files.deleteIfExists(this.templateFile);
    }

    private static WorldInstanceRequest request(final String instanceId) {
        return new WorldInstanceRequest(
                new WorldTemplateRef(TEMPLATE_ID, Path.of("/data/templates/arena-desert.slime")), instanceId);
    }

    private WorldInstanceRequest requestWithTemplate(final String instanceId) {
        return new WorldInstanceRequest(new WorldTemplateRef(TEMPLATE_ID, this.templateFile), instanceId);
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

    private WorldInstance createAndAwait(final String instanceId) {
        return await(this.service.create(requestWithTemplate(instanceId)));
    }

    @Test
    void createSucceedsAndIsQueryable() {
        final WorldInstance instance = createAndAwait("match-1");

        assertEquals(WorldInstanceState.ACTIVE, instance.state());
        assertEquals("match-1", instance.instanceId());
        assertEquals(TEMPLATE_ID, instance.templateId());
        assertTrue(instance.worldName().startsWith("pepper_inst_"), "世界名由 provider 生成");
        assertNotNull(Bukkit.getWorld(instance.worldName()), "Bukkit 世界已注册");
        assertEquals(instance, this.service.find("match-1").orElseThrow());
        assertTrue(this.service.instances().contains(instance));
    }

    @Test
    void createWithMissingTemplateFailsAndLeavesNoResidue() {
        final WorldTemplateRef missing = new WorldTemplateRef("missing", Path.of("/nonexistent/arena.slime"));
        final CompletableFuture<WorldInstance> future =
                this.service.create(new WorldInstanceRequest(missing, "match-2"));

        final CompletionException ex = assertThrows(CompletionException.class, () -> await(future));
        assertInstanceOf(WorldProviderException.class, ex.getCause());
        assertEquals(WorldProviderError.TEMPLATE_ERROR, ((WorldProviderException) ex.getCause()).error());
        assertTrue(this.service.find("match-2").isEmpty(), "失败后无半成品实例");
        assertTrue(this.service.instances().isEmpty());
    }

    @Test
    void duplicateInstanceIdIsRejected() {
        createAndAwait("match-1");

        final CompletableFuture<WorldInstance> second = this.service.create(request("match-1"));
        final CompletionException ex = assertThrows(CompletionException.class, second::join);
        assertInstanceOf(WorldProviderException.class, ex.getCause());
        assertEquals(WorldProviderError.INSTANCE_ID_CONFLICT, ((WorldProviderException) ex.getCause()).error());
        assertEquals(1, this.service.instances().size(), "既有实例保留");
    }

    @Test
    void unloadNonEmptyWorldWithRequireEmptyFailsAndKeepsInstance() {
        final WorldInstance instance = createAndAwait("match-1");
        final Player player = this.server.addPlayer();
        player.teleport(instance.world().getSpawnLocation());

        final CompletableFuture<Void> unload = this.service.unload("match-1", UnloadOptions.discardWhenEmpty());

        final CompletionException ex = assertThrows(CompletionException.class, () -> await(unload));
        assertInstanceOf(WorldProviderException.class, ex.getCause());
        assertEquals(WorldProviderError.WORLD_NOT_EMPTY, ((WorldProviderException) ex.getCause()).error());
        assertTrue(this.service.find("match-1").isPresent(), "拒卸后实例保留");
        assertNotNull(Bukkit.getWorld(instance.worldName()), "拒卸后世界保持加载");
    }

    @Test
    void unloadEmptyWorldSucceeds() {
        final WorldInstance instance = createAndAwait("match-1");

        final CompletableFuture<Void> unload = this.service.unload("match-1", UnloadOptions.discardWhenEmpty());
        await(unload);

        assertTrue(this.service.find("match-1").isEmpty());
        assertNull(Bukkit.getWorld(instance.worldName()), "世界已卸载");
    }

    @Test
    void unloadWithSaveTrueIsRejected() {
        createAndAwait("match-1");

        final CompletableFuture<Void> unload = this.service.unload("match-1", new UnloadOptions(true, false));
        final CompletionException ex = assertThrows(CompletionException.class, unload::join);
        assertInstanceOf(WorldProviderException.class, ex.getCause());
        assertEquals(WorldProviderError.INVALID_REQUEST, ((WorldProviderException) ex.getCause()).error());
    }

    @Test
    void unloadUnknownInstanceIdIsIdempotentSuccess() {
        this.service.unload("ghost", UnloadOptions.discardWhenEmpty()).join();
    }

    @Test
    void createAfterCloseFailsWithServiceClosed() {
        this.service.close();

        final CompletableFuture<WorldInstance> future = this.service.create(request("match-9"));
        final CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(WorldProviderException.class, ex.getCause());
        assertEquals(WorldProviderError.SERVICE_CLOSED, ((WorldProviderException) ex.getCause()).error());
    }

    @Test
    void unloadAllUnloadsEveryInstance() {
        final WorldInstance first = createAndAwait("match-a");
        final WorldInstance second = createAndAwait("match-b");

        final CompletableFuture<Void> all = this.service.unloadAll(UnloadOptions.discardForShutdown());
        await(all);

        assertTrue(this.service.instances().isEmpty());
        assertNull(Bukkit.getWorld(first.worldName()));
        assertNull(Bukkit.getWorld(second.worldName()));
    }

    @Test
    void providerInfoIsExposed() {
        final ltd.pepper.lib.world.WorldProviderInfo info = this.service.providerInfo();
        assertEquals("pepper-lib-aswm", info.providerId());
        assertNotNull(info.providerVersion());
        assertNotNull(info.supportedSlimeApiRange());
    }

    @Test
    void serviceIsRegisteredUnderCoreInterface() {
        // 契约：provider 注册的必须是核心 SPI 类型（服务发现按接口匹配）。
        assertEquals(AswmInstanceWorldService.class.getName(), "ltd.pepper.lib.aswm.provider.AswmInstanceWorldService");
        assertTrue(InstanceWorldService.class.isAssignableFrom(AswmInstanceWorldService.class));
    }
}
