package ltd.pepper.lib.plugin;

import java.util.Set;
import ltd.pepper.lib.runtime.PepperLibRuntime;

/**
 * 前置插件内置运行时服务实现：版本取自插件描述（单一来源），
 * 能力集由 {@link CapabilityResolver} 按服务器版本决策（自适应加载 §2.2）。
 */
final class DefaultPepperLibRuntime implements PepperLibRuntime {

    private final String apiVersion;
    private final Set<String> capabilities;

    DefaultPepperLibRuntime(final String apiVersion, final Set<String> capabilities) {
        this.apiVersion = apiVersion;
        this.capabilities = Set.copyOf(capabilities);
    }

    @Override
    public String apiVersion() {
        return this.apiVersion;
    }

    @Override
    public boolean supports(final String capability) {
        // null 与未知能力一致返回 false（契约：未知/未声明能力不抛异常）。
        return capability != null && this.capabilities.contains(capability);
    }
}
