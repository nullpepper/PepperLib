package io.pepper.lib.persist;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * 可持久化、全量加载的泛型键值存储。
 *
 * <p>语义契约：
 * <ul>
 *   <li><b>全量加载</b>：构造时读取整个文件进内存（文件不存在 → 空表；
 *       损坏 → {@code IOException} 构造失败，启动即暴露）；</li>
 *   <li><b>异步写盘</b>：{@code put}/{@code remove} 标记修改并排异步写任务
 *       （写中合并：写盘期间的修改并入下一次写；单飞：同一时刻至多一个写任务）；</li>
 *   <li><b>原子写</b>：先写同目录临时文件再原子 rename——崩溃/断电时旧文件
 *       完好或新文件完整，无中间态；</li>
 *   <li><b>同步兜底</b>：{@link #flush()} 在调用线程同步落盘（关闭前/检查点
 *       调用防丢最后一批修改）；异步写失败保留 dirty 状态（下次修改重试）；</li>
 *   <li><b>关闭</b>：{@link #close()} 内部 flush；调用方负责关闭前保证
 *       executor 仍可执行（异步写任务不因 executor 关闭而静默丢失）。</li>
 * </ul>
 *
 * <p>纯 JDK 零依赖；序列化经 {@link StoreCodec} 注入。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface PersistentStore<K, V> extends AutoCloseable {

    /** @return 键对应的值；不存在为空 */
    Optional<V> get(K key);

    /** @return 键对应的值；不存在返回 {@code defaultValue} */
    V getOrDefault(K key, V defaultValue);

    /**
     * 写入/覆盖键值（异步写盘）。
     *
     * @param key   键（非 null）
     * @param value 值（非 null）
     */
    void put(K key, V value);

    /**
     * 删除键（异步写盘）。
     *
     * @return 是否存在并被删除
     */
    boolean remove(K key);

    /** @return 当前全量数据的不可变快照 */
    Map<K, V> snapshot();

    /** @return 当前条目数 */
    int size();

    /**
     * 同步落盘（检查点/关闭前调用；写盘失败向调用方抛异常，不静默）。
     *
     * @throws IOException 写盘失败
     */
    void flush() throws IOException;

    /** 关闭：同步 flush 后释放资源（幂等）。 */
    @Override
    void close() throws IOException;
}
