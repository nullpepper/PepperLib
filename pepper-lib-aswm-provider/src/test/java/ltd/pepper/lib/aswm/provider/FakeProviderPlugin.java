package ltd.pepper.lib.aswm.provider;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * MockBukkit 测试插件：AswmInstanceWorldService 构造需要真实 JavaPlugin 实例。
 * 注意：MockBukkit 经 ByteBuddy 代理插件类，必须为非 final。
 */
public class FakeProviderPlugin extends JavaPlugin {}
