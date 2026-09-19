# Doublecheck spec

## Goal
按 PepperLib-Adaptive-Loading-Plan.md 实施自适应加载：前置插件 api-version 降至 '1.18'，onEnable 用 getMinecraftVersion() 检测版本（阈值 [1,21,0]），低版本（1.18.2~1.20.6）禁用 gui-host 能力（supports("gui-host")=false + warning 日志），新版本行为不变；ServerVersions 与能力接线全部红绿测试；Paper 1.18.2 + Java 17 legacy smoke 进 CI；发布 0.3.0。

## Scope
PepperLib 仓库内：两个描述符 api-version；新类 ServerVersions（io.pepper.lib.runtime）+ 测试；PepperLibRuntime.CAP_GUI_HOST 常量（新增，非签名变更）；DefaultPepperLibRuntime 能力集；新类 CapabilityResolver（plugin 包）+ 测试；PepperLibPlugin.onEnable 接线；README/双加载文档；scripts/paper-smoke.sh legacy 模式；.github/workflows/ci.yml 新 job；build.gradle.kts 版本 0.3.0；CHANGELOG.md。

## Acceptance criteria
1) 前置插件 + shade 示例 api-version 降至 '1.18'，thin smoke（26.1.2）回归全绿；2) ServerVersions（lib 公共 API，纯 Java）红绿测试覆盖新旧格式解析与 [1,21,0] 阈值边界；3) 能力接线：CAP_GUI_HOST 常量、DefaultPepperLibRuntime 能力集、CapabilityResolver 纯函数（"1.18.2"→{}、"1.21"→{gui-host}、"26.1.2"→{gui-host}）、onEnable 检测 + warning 日志，全部红绿通过；4) README 生态边界 + 双加载文档消费者契约小节落地；5) legacy smoke（Paper 1.18.2 + Java 17，CI job）：PepperLib 启用 ✓、warning 日志含 gui-host 禁用 ✓、无崩溃 ✓；6) 版本 0.3.0 + CHANGELOG + japicmp（基线 0.2.0）通过 + ./gradlew build 全绿（含字节码守卫 major 61）。

## Failure modes
1) 1.18.2 服务器运行时发现 InventoryView 之外的不兼容点（类缺失/形态差异）→ 按同类机制细化禁用粒度或小范围兼容代码，报告后处理；2) Paper 1.18.2 jar 无法下载/JDK 17 不可用 → legacy smoke 走 CI（setup-java 17），本地不阻塞；3) MockBukkit 无法桩 getMinecraftVersion → 检测决策为纯函数（CapabilityResolver），MockBukkit 只测集成；4) api-version '1.18' 行为开关副作用 → thin smoke 回归验证；5) japicmp 拦截（接口改动）→ 本方案零接口签名变更（只新增工具类/常量），若拦截则报告。

## Priorities
正确性优先：全量绿门 + legacy smoke 是硬验收；红绿纪律贯穿阶段 2/3；api-version 拆墙先行（阶段 1）且必须过 thin smoke 回归；文档与实现一致；改动面最小化。

## Non-goals
不改 GuiHolder 公共 API（路线 B）；不保证低版本上能使用 gui-host；不降编译基线（保持 26.1 编译/Java 17 字节码）；不迁移 Union/Claim；不支持低于 1.18.2 的服务器（1.16.x 需 Java 16 运行时）；不发布到远程仓库（publishToMavenLocal 即可）；不改 Union/Claim/BindManager/TrashBin/Minecart 任何文件。
