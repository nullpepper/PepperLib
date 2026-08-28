/**
 * 可持久化泛型键值存储（{@code PersistentStore}）：构造时全量加载文件，
 * 修改异步写盘（写中合并 + 原子写崩溃一致 + flush 同步兜底）。
 *
 * <p>双消费者共设计（提取纪律）：PVP 竞技场（地图/模板配置、比赛结果持久化）
 * 与漂流瓶插件（类似持久化需求）。</p>
 *
 * <p><b>耦合度</b>：纯 JDK（零第三方依赖）；序列化经 {@link StoreCodec}
 * 由消费者注入（Gson/Jackson 等）。</p>
 *
 * <p><b>状态</b>：Experimental——两消费者接入并上线后转正冻结。</p>
 */
package ltd.pepper.lib.persist;
