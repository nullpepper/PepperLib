package io.pepper.lib.plugin;

import io.pepper.lib.runtime.PepperLibRuntime;

/**
 * 前置插件内置运行时服务实现：版本取自插件描述（单一来源），
 * 能力声明当前为空集（后续能力在此扩展）。
 */
final class DefaultPepperLibRuntime implements PepperLibRuntime {

    private final String apiVersion;

    DefaultPepperLibRuntime(final String apiVersion) {
        this.apiVersion = apiVersion;
    }

    @Override
    public String apiVersion() {
        return this.apiVersion;
    }

    @Override
    public boolean supports(final String capability) {
        // 当前无能力声明；后续能力（如 "runtime" / "gui"）在此扩展。
        return false;
    }
}
