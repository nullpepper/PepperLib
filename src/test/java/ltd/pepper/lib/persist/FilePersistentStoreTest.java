package ltd.pepper.lib.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PersistentStore} 文件实现契约：全量加载、修改异步写盘（写中合并）、
 * 原子写崩溃一致、flush 同步兜底、损坏文件加载即失败。
 */
class FilePersistentStoreTest {

    @TempDir
    Path tempDir;

    private Path file;
    private ExecutorService pool;

    /** 测试桩：{@code Map<String,String>} ↔ 行格式（{@code k=v}）。 */
    private static final StoreCodec<String, String> LINE_CODEC = new StoreCodec<>() {
        @Override
        public byte[] encode(final Map<String, String> table) {
            final StringBuilder sb = new StringBuilder();
            table.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Map<String, String> decode(final byte[] data) throws IOException {
            final Map<String, String> table = new HashMap<>();
            for (final String line : new String(data, StandardCharsets.UTF_8).split("\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                final int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new IOException("invalid line: " + line);
                }
                table.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return table;
        }
    };

    @BeforeEach
    void setUp() {
        this.file = this.tempDir.resolve("store.data");
        this.pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        this.pool.shutdownNow();
    }

    private PersistentStore<String, String> open() throws IOException {
        return PersistentStores.fileBacked(this.file, LINE_CODEC, this.pool);
    }

    private void awaitFileContains(final String expected) throws Exception {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(this.file)) {
                final String content = Files.readString(this.file, StandardCharsets.UTF_8);
                if (content.contains(expected)) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("文件未在超时内包含: " + expected);
    }

    @Test
    void missingFileStartsEmpty() throws IOException {
        try (PersistentStore<String, String> store = open()) {
            assertEquals(0, store.size());
            assertTrue(store.get("a").isEmpty());
        }
    }

    @Test
    void putsPersistOnFlushAndReload() throws IOException {
        try (PersistentStore<String, String> store = open()) {
            store.put("a", "1");
            store.put("b", "2");
            store.flush();
        }
        try (PersistentStore<String, String> reloaded = open()) {
            assertEquals("1", reloaded.get("a").orElseThrow());
            assertEquals("2", reloaded.get("b").orElseThrow());
            assertEquals(2, reloaded.size());
        }
    }

    @Test
    void loadsExistingFileOnConstruction() throws Exception {
        Files.write(this.file, LINE_CODEC.encode(Map.of("a", "1", "b", "2")));
        try (PersistentStore<String, String> store = open()) {
            assertEquals("2", store.get("b").orElseThrow());
            assertEquals("1", store.getOrDefault("a", "x"));
        }
    }

    @Test
    void removeDeletesEntry() throws IOException {
        try (PersistentStore<String, String> store = open()) {
            store.put("a", "1");
            store.flush();
            assertTrue(store.remove("a"));
            assertFalse(store.remove("a"));
            store.flush();
        }
        try (PersistentStore<String, String> reloaded = open()) {
            assertTrue(reloaded.get("a").isEmpty());
        }
    }

    @Test
    void asyncWritesMergeBursts() throws Exception {
        final PersistentStore<String, String> store = open();
        // 不 flush：快速连续修改，单飞合并最终写盘。
        for (int i = 1; i <= 20; i++) {
            store.put("k", "v" + i);
        }
        awaitFileContains("k=v20");
        try (PersistentStore<String, String> reloaded = open()) {
            assertEquals(Optional.of("v20"), reloaded.get("k"));
        }
        store.close();
    }

    @Test
    void asyncWriteLeavesNoTempResidue() throws Exception {
        final PersistentStore<String, String> store = open();
        store.put("a", "1");
        store.flush();
        try (var files = Files.list(this.tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "原子写后无临时文件残留");
        }
        store.close();
    }

    @Test
    void corruptedFileThrowsOnLoad() throws IOException {
        Files.writeString(this.file, "garbage-not-a-store", StandardCharsets.UTF_8);
        assertThrows(IOException.class, this::open);
    }

    @Test
    void snapshotIsImmutable() throws IOException {
        try (PersistentStore<String, String> store = open()) {
            store.put("a", "1");
            final Map<String, String> snapshot = store.snapshot();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"));
            assertEquals(1, store.size(), "快照修改不影响原表");
        }
    }

    @Test
    void getOrDefaultFallsBack() throws IOException {
        try (PersistentStore<String, String> store = open()) {
            assertEquals("default", store.getOrDefault("missing", "default"));
        }
    }

    @Test
    void flushAfterCloseIsSafe() throws Exception {
        final PersistentStore<String, String> store = open();
        store.put("a", "1");
        store.flush();
        store.close();
        // 关闭后再 flush：幂等（不抛）。
        store.flush();
    }
}
