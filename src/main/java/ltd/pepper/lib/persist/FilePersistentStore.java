package ltd.pepper.lib.persist;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * 文件后端 {@link PersistentStore} 实现。
 *
 * <p>写盘语义（单飞合并）：
 * <ul>
 *   <li>{@code put}/{@code remove} → {@code dirty=true} + 若无线程在写则排一个写任务；</li>
 *   <li>写任务：锁内重置 {@code writeScheduled}、取 dirty 快照 → 锁外序列化 +
 *       临时文件 + 原子 rename；写盘期间的修改排入下一次写（合并）；</li>
 *   <li>异步写失败：恢复 {@code dirty=true}（下次修改重试）+ 日志。</li>
 * </ul>
 * 竞态安全论证：每次修改后要么本次写快照包含、要么已排后续写任务——
 * 唯一丢数据窗口是进程在写盘完成前崩溃（异步本质），由 {@code flush()} 兜底。</p>
 */
final class FilePersistentStore<K, V> implements PersistentStore<K, V> {

    private static final Logger LOGGER = Logger.getLogger(FilePersistentStore.class.getName());

    private final Path file;
    private final Path tempFile;
    private final StoreCodec<K, V> codec;
    private final Executor writeExecutor;
    private final ConcurrentMap<K, V> table = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile boolean writeScheduled;
    private volatile boolean dirty;

    FilePersistentStore(final Path file, final StoreCodec<K, V> codec, final Executor writeExecutor)
            throws IOException {
        this.file = file;
        this.tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.codec = codec;
        this.writeExecutor = writeExecutor;
        if (Files.isRegularFile(file)) {
            final byte[] data = Files.readAllBytes(file);
            if (data.length > 0) {
                this.table.putAll(codec.decode(data));
            }
        }
    }

    @Override
    public Optional<V> get(final K key) {
        return Optional.ofNullable(this.table.get(key));
    }

    @Override
    public V getOrDefault(final K key, final V defaultValue) {
        return this.table.getOrDefault(key, defaultValue);
    }

    @Override
    public void put(final K key, final V value) {
        this.table.put(key, value);
        this.dirty = true;
        scheduleWrite();
    }

    @Override
    public boolean remove(final K key) {
        final boolean removed = this.table.remove(key) != null;
        if (removed) {
            this.dirty = true;
            scheduleWrite();
        }
        return removed;
    }

    @Override
    public Map<K, V> snapshot() {
        return Map.copyOf(this.table);
    }

    @Override
    public int size() {
        return this.table.size();
    }

    @Override
    public void flush() throws IOException {
        // 取消 pending 异步写并同步落盘：写盘全程持锁，与异步写互斥
        // （临时文件无并发竞争）。
        synchronized (this.writeLock) {
            this.writeScheduled = false;
            writeSnapshot();
        }
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    private void scheduleWrite() {
        if (this.writeScheduled) {
            return;
        }
        synchronized (this.writeLock) {
            if (this.writeScheduled) {
                return;
            }
            this.writeScheduled = true;
        }
        this.writeExecutor.execute(this::writeAsync);
    }

    private void writeAsync() {
        synchronized (this.writeLock) {
            this.writeScheduled = false;
            if (!this.dirty) {
                return;
            }
            this.dirty = false;
            try {
                writeSnapshot();
            } catch (final IOException e) {
                // 保留 dirty：下次修改重试；flush() 同步路径仍可兜底。
                this.dirty = true;
                LOGGER.warning("PersistentStore 异步写盘失败: " + this.file + " — " + e.getMessage());
            }
        }
    }

    private void writeSnapshot() throws IOException {
        final Map<K, V> snapshot = Map.copyOf(this.table);
        final byte[] data = this.codec.encode(snapshot);
        Files.write(this.tempFile, data);
        try {
            Files.move(this.tempFile, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            // 文件系统不支持原子移动：退化为普通替换（仍无中间态写入目标文件）。
            Files.move(this.tempFile, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
