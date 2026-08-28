package ltd.pepper.lib.persist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link PersistentStore} 工厂：文件后端实现（全量加载 + 异步写盘 + 原子写）。
 */
public final class PersistentStores {

    private PersistentStores() {}

    /**
     * 创建文件后端存储。
     *
     * @param file          数据文件（不存在则从空表开始；父目录须已存在）
     * @param codec         整表序列化器（消费者实现）
     * @param writeExecutor 异步写盘执行器（须能串行执行写任务；建议单线程池）
     * @throws IOException 文件存在但损坏/不可读
     */
    public static <K, V> PersistentStore<K, V> fileBacked(
            final Path file, final StoreCodec<K, V> codec, final Executor writeExecutor) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(writeExecutor, "writeExecutor");
        return new FilePersistentStore<>(file, codec, writeExecutor);
    }
}
