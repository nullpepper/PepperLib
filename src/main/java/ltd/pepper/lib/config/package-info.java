/**
 * 配置文件工具链（设计文档 {@code docs/yaml-config-system-design.md} §7/§9）：文件生命周期
 * （{@link ltd.pepper.lib.config.ConfigFile}）、问题收集（{@link ltd.pepper.lib.config.IssueCollector}）、
 * 类型化读取（{@link ltd.pepper.lib.config.Values}）、未知键检测（{@link ltd.pepper.lib.config.UnknownKeys}）、
 * 版本迁移（{@link ltd.pepper.lib.config.ConfigVersions}）与升级补键（{@link ltd.pepper.lib.config.UpgradePatch}），
 * 纯 JDK、零 Bukkit 依赖。
 */
package ltd.pepper.lib.config;
