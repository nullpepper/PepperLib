package io.pepper.lib.persist;

import java.io.IOException;
import java.util.Map;

/**
 * 整表序列化器（消费者提供实现）：{@link PersistentStore} 全量加载/全量写盘
 * 的编解码契约。PepperLib 保持纯 JDK 零第三方依赖——Gson/Jackson 等由
 * 消费者自行实现本接口。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface StoreCodec<K, V> {

    /**
     * 编码整表为字节。
     *
     * @param table 当前全量数据（调用方快照，实现不得修改）
     */
    byte[] encode(Map<K, V> table);

    /**
     * 解码字节为整表。
     *
     * @throws IOException 数据损坏/格式非法（构造期即暴露，不静默空表丢数据）
     */
    Map<K, V> decode(byte[] data) throws IOException;
}
