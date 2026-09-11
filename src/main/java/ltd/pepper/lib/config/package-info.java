/**
 * 配置文件工具链（设计文档 {@code docs/yaml-config-system-design.md} §7/§9）：
 *
 * <ul>
 *   <li>生命周期：{@link ltd.pepper.lib.config.ConfigFile}（copy-once / readUtf8 BOM / writeAtomic）；
 *   <li>问题收集：{@link ltd.pepper.lib.config.IssueCollector}/{@link ltd.pepper.lib.config.ConfigIssue}；
 *   <li>类型化读取：{@link ltd.pepper.lib.config.Values}/{@link ltd.pepper.lib.config.UnknownKeys}；
 *   <li>版本迁移与升级补键：{@link ltd.pepper.lib.config.ConfigVersions}/{@link ltd.pepper.lib.config.UpgradePatch}；
 *   <li>自研绑定/注释/运行时存储（0.12.0，替代 ConfigLib 绑定层）：
 *       {@link ltd.pepper.lib.config.Bindings} 注解驱动绑定（{@code @ConfigModel}/
 *       {@code @ConfigPath}/{@code @ConfigComment}/{@code @ConfigRange}/{@code @Codec}）、
 *       {@link ltd.pepper.lib.config.YamlComments} 注释归属解析、
 *       {@link ltd.pepper.lib.config.ConfigDoc} 字节保真编辑（含子节点遍历
 *       {@code children}、节内默认合并 {@code mergeDefaults}、文档头注释
 *       {@code header}/{@code withHeader}）、
 *       {@link ltd.pepper.lib.config.YamlScalar} 标量/flow list/flow map 发射、
 *       {@link ltd.pepper.lib.config.ConfigCodec} 自定义类型序列化注册点、
 *       {@link ltd.pepper.lib.config.ConfigFileStore}/{@link ltd.pepper.lib.config.ConfigGroup}
 *       运行时存储与多文件分组。
 *   <li>绑定能力增量（0.13.0，P3 首批三家 TreeCut→Minecart→Union 迁移支撑）：
 *       Map 一等绑定（数值/字符串键映射）、装载后处理钩子
 *       {@link ltd.pepper.lib.config.ConfigPostLoad}、{@code @ConfigRange(clamp=true)}
 *       夹紧模式、NaN/Infinity 拦截、枚举大小写不敏感、emit 兄弟节修复。
 *   <li>ConfigMe 对照吸收（0.14.0，对照文档 {@code docs/ConfigMe-vs-PepperLib.md} §7）：
 *       类型系统扩展（{@code Optional}/{@code Set}/{@code T[]}/
 *       {@code LocalDate}/{@code LocalTime}/{@code LocalDateTime} 一等绑定）、
 *       值级合法性信号 {@link ltd.pepper.lib.config.ConfigValues}
 *       （PRESENT/MISSING/INVALID，对齐 {@code PropertyValue.isValidInResource}）、
 *       可插拔迁移服务 {@link ltd.pepper.lib.config.ConfigMigration}/
 *       {@link ltd.pepper.lib.config.ConfigMigrations}
 *       （对齐 {@code MigrationService}/{@code PlainMigrationService}，
 *       保守：只改内存不自动落盘）。
 * </ul>
 *
 * 纯 JDK、零 Bukkit 依赖。
 */
package ltd.pepper.lib.config;
