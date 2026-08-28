/**
 * 世界实例能力（{@code Experimental}）：与具体 Slime 实现解耦的异步实例世界 SPI。
 *
 * <p>公共签名耦合 Bukkit 类型（{@code org.bukkit.World}），零第三方实现依赖
 * （ASWM 类型只出现在独立 provider 工程）。双消费者共设计：PVP/PVE 竞技场插件，
 * 语义一致（结束 → 清场 → 卸载）。provider 经 Bukkit ServicesManager 注册；
 * 跨插件互通仅限前置插件模式（shade 消费者因类重定位无法经 ServicesManager 互通）。</p>
 *
 * <p><b>耦合度</b>：Paper-bound（依赖 Bukkit 类型与 ServicesManager）。</p>
 *
 * <p><b>状态</b>：Experimental——两个消费者接入并上线后转正冻结。</p>
 */
package io.pepper.lib.world;
