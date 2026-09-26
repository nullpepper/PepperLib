package ltd.pepper.lib.dialog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import ltd.pepper.lib.task.PepperScheduler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * {@link DialogHost} 的点击回调契约：主线程调度、业务值归属、异常隔离与日志、非玩家
 * audience 丢弃、调度失败隔离，以及打开路径的失败隔离。
 *
 * <p>线程契约经注入的 {@link PepperScheduler} 用记录型调度器验证（不依赖服务端 tick）；
 * 业务回调本身是纯闭包，无需假服务器验证“真实打开效果”。</p>
 */
class DialogHostCallbackTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private LogCapture logCapture;

    /** 记录型调度器：isMainThread 可配，runTask 只入队不执行，runTask 可配置为抛异常。 */
    private static final class RecordingScheduler implements PepperScheduler {

        private final boolean mainThread;
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean dispatchFails;

        RecordingScheduler(final boolean mainThread) {
            this.mainThread = mainThread;
        }

        @Override
        public boolean isMainThread() {
            return this.mainThread;
        }

        @Override
        public void runTask(final Runnable task) {
            if (this.dispatchFails) {
                throw new IllegalStateException("plugin disabled");
            }
            this.tasks.addLast(task);
        }

        @Override
        public void runAsync(final Runnable task) {
            this.tasks.addLast(task);
        }

        @Override
        public void runRepeating(final Runnable task, final long delayTicks, final long periodTicks) {}

        @Override
        public <T> CompletableFuture<T> supplyOnMain(final Supplier<T> supplier) {
            return CompletableFuture.completedFuture(supplier.get());
        }

        void drain() {
            while (!this.tasks.isEmpty()) {
                this.tasks.removeFirst().run();
            }
        }

        int pending() {
            return this.tasks.size();
        }
    }

    /** 记录 plugin 日志的 JUL handler（前置插件真实日志出口）。 */
    private final class LogCapture extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(final LogRecord record) {
            this.records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin();
        this.player = this.server.addPlayer("Tester");
        final LogCapture capture = new LogCapture();
        this.logCapture = capture;
        this.plugin.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        this.plugin.getLogger().removeHandler(this.logCapture);
        MockBukkit.unmock();
    }

    /** 每个按钮一个业务值；回调记录 {@code 业务值@玩家名} 以同时验证 player 传递。 */
    private static MultiActionDialog<String> dialogOf(final Consumer<String> recorder, final String... values) {
        final MultiActionDialog.Builder<String> builder = MultiActionDialog.multiAction(Component.text("测试"));
        for (final String value : values) {
            builder.button(Component.text("label-" + value), value, (v, p) -> recorder.accept(v + "@" + p.getName()));
        }
        return builder.build();
    }

    private LogRecord singleLogRecord() {
        assertEquals(1, this.logCapture.records.size(), "必须恰好记录一条日志：" + this.logCapture.records);
        return this.logCapture.records.get(0);
    }

    @Test
    void offThreadClickIsDeferredToMainThread() {
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final List<String> fired = new ArrayList<>();
        final DialogActionCallback callback =
                host.callbackFor(dialogOf(fired::add, "v1").buttons().get(0));

        callback.accept(null, this.player);

        assertEquals(List.of(), fired, "非主线程点击不得当场执行业务回调");
        assertEquals(1, scheduler.pending(), "回调必须交给调度器回主线程");
        scheduler.drain();
        assertEquals(List.of("v1@Tester"), fired, "主线程执行时必须带上按钮业务值与点击者");
    }

    @Test
    void mainThreadClickRunsInline() {
        final RecordingScheduler scheduler = new RecordingScheduler(true);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final List<String> fired = new ArrayList<>();
        final DialogActionCallback callback =
                host.callbackFor(dialogOf(fired::add, "v1").buttons().get(0));

        callback.accept(null, this.player);

        assertEquals(List.of("v1@Tester"), fired, "已在主线程时直接执行，不额外调度");
        assertEquals(0, scheduler.pending());
    }

    @Test
    void eachButtonFiresItsOwnValueBoundClosure() {
        final DialogHost host = new DialogHost(this.plugin, new RecordingScheduler(true));
        final List<String> fired = new ArrayList<>();
        final MultiActionDialog<String> dialog = dialogOf(fired::add, "v0", "v1", "v2");

        host.callbackFor(dialog.buttons().get(1)).accept(null, this.player);
        assertEquals(List.of("v1@Tester"), fired, "点第 2 个按钮只能触发第 2 个闭包");
        assertFalse(fired.contains("v0@Tester"), "不得触发第 1 个按钮的闭包");
        assertFalse(fired.contains("v2@Tester"), "不得触发第 3 个按钮的闭包");

        host.callbackFor(dialog.buttons().get(0)).accept(null, this.player);
        host.callbackFor(dialog.buttons().get(2)).accept(null, this.player);
        assertEquals(List.of("v1@Tester", "v0@Tester", "v2@Tester"), fired, "按钮与闭包一一对应且各自只触发一次");
    }

    @Test
    void callbackExceptionIsSwallowedAndLogged() {
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(Component.text("测试"))
                .button(Component.text("炸"), "v1", (value, p) -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        final DialogActionCallback callback = host.callbackFor(dialog.buttons().get(0));

        assertDoesNotThrow(() -> callback.accept(null, this.player), "回调异常不得冒泡到服务端对话框路径");
        assertDoesNotThrow(scheduler::drain, "调度执行时异常同样不得冒泡");

        final LogRecord record = singleLogRecord();
        assertEquals(Level.WARNING, record.getLevel(), "隔离的异常必须作为 warning 记录");
        assertNotNull(record.getThrown(), "日志必须携带原始异常");
        assertEquals("boom", record.getThrown().getMessage());
    }

    @Test
    void exitButtonCallbackIsDispatchedAndGuarded() {
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final List<String> fired = new ArrayList<>();
        final MultiActionDialog<String> dialog = MultiActionDialog.<String>multiAction(Component.text("测试"))
                .button(Component.text("A"), "a", (value, p) -> {})
                .exitButton(Component.text("关闭"), null, () -> {
                    fired.add("exit");
                    throw new IllegalStateException("exit-boom");
                })
                .build();

        final DialogActionCallback callback = host.callbackForExit(dialog.exitButton());
        callback.accept(null, this.player);
        assertEquals(List.of(), fired, "退出回调同样先回主线程");
        assertDoesNotThrow(scheduler::drain);
        assertEquals(List.of("exit"), fired, "退出动作必须执行");
        assertEquals("exit-boom", singleLogRecord().getThrown().getMessage(), "退出动作异常同样被隔离记录");
    }

    @Test
    void nonPlayerAudienceClickIsDroppedAndLogged() {
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final List<String> fired = new ArrayList<>();
        final DialogActionCallback callback =
                host.callbackFor(dialogOf(fired::add, "v1").buttons().get(0));

        callback.accept(null, mock(Audience.class));

        assertEquals(List.of(), fired, "非玩家 audience 不得执行业务回调");
        assertEquals(0, scheduler.pending(), "丢弃的点击不得占用主线程调度");
        final LogRecord record = singleLogRecord();
        assertEquals(Level.WARNING, record.getLevel());
    }

    @Test
    void dispatchFailureIsSwallowedAndLogged() {
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        scheduler.dispatchFails = true;
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final List<String> fired = new ArrayList<>();
        final DialogActionCallback callback =
                host.callbackFor(dialogOf(fired::add, "v1").buttons().get(0));

        assertDoesNotThrow(() -> callback.accept(null, this.player), "调度失败（如插件已禁用）不得冒泡");

        assertEquals(List.of(), fired);
        final LogRecord record = singleLogRecord();
        assertEquals(Level.WARNING, record.getLevel());
        assertNotNull(record.getThrown());
    }

    @Test
    void diagnosticsFailureNeverPropagatesToTheServer() {
        // 诊断路径（这里让 player.getName() 抛异常）本身也必须被隔离：异常契约不允许有漏洞，
        // 否则 Paper 的对话框处理路径仍会收到我们的异常。
        final RecordingScheduler scheduler = new RecordingScheduler(false);
        final DialogHost host = new DialogHost(this.plugin, scheduler);
        final Player broken = mock(Player.class);
        when(broken.getName()).thenThrow(new IllegalStateException("no-name"));
        final DialogActionCallback callback =
                host.callbackFor(dialogOf(v -> {}, "v1").buttons().get(0));

        assertDoesNotThrow(() -> callback.accept(null, broken), "诊断失败不得冒泡到服务端对话框路径");
        assertDoesNotThrow(scheduler::drain, "调度执行同样不得冒泡");
    }

    @Test
    void openFailureIsSwallowedAndLoggedOnMainThread() {
        // 无服务端 Dialog provider 时渲染必然失败——这正好是打开路径的失败隔离契约。
        final DialogHost host = new DialogHost(this.plugin, new RecordingScheduler(true));
        final MultiActionDialog<String> dialog = dialogOf(v -> {}, "v1");

        assertDoesNotThrow(() -> host.open(this.player, dialog), "打开失败不得冒泡到调用方线程");
        assertTrue(this.logCapture.records.size() >= 1, "打开失败必须记录日志");
    }
}
