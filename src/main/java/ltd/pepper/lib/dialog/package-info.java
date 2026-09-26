/**
 * Paper 原生 Dialog（{@code io.papermc.paper.dialog.Dialog}）通用助手：多操作按钮对话框的
 * 装配、按钮级业务值回调、主线程调度与异常隔离。
 *
 * <p><b>耦合度</b>：Paper-bound：公共签名引用 Paper 的 Dialog API
 * （{@code io.papermc.paper.dialog.Dialog}、{@code io.papermc.paper.registry.data.dialog.*}）
 * 与 Adventure 的 {@code net.kyori.adventure.text.Component}；均为 compileOnly，lib 不打包、
 * 不传递，由服务端运行期提供（与 {@code ltd.pepper.lib.gui} 同约定）。</p>
 *
 * <p><b>线程模型</b>：{@link ltd.pepper.lib.dialog.DialogHost} 的打开入口与点击回调适配均可
 * 从任意线程调用，内部经调度器回 Minecraft 主线程；业务回调统一在主线程执行，不依赖
 * 「Paper 的对话框点击回调本就在主线程」这一外部前提。</p>
 *
 * <p><b>异常契约</b>：业务回调与打开路径抛出的任何异常都被捕获并记入插件日志，绝不上抛到
 * 服务端的对话框处理路径。</p>
 *
 * <p><b>版本门控</b>：Paper Dialog API 需 MC 1.21.6+ 服务端。本包<b>不</b>参与
 * {@code @MinMinecraftVersion} 能力注册（能力注册表由 {@code gui-host} /
 * {@code world-instance} 精确集合守卫），消费者须自行按服务器版本决定是否启用对话框入口。</p>
 */
package ltd.pepper.lib.dialog;
