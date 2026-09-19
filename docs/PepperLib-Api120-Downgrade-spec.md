# Doublecheck spec

## Goal
将 PepperLib 全仓主源码编译基线从 Paper API 26.1 降到 1.20.1（前置插件 + shade 示例同步），使 lib 全部模块（含 Bukkit 耦合模块）可在 Paper 1.20.1+（Java 17+）服务器加载，同时保持测试在 26.1 上运行、字节码仍为 Java 17（major 61），./gradlew build 全绿。

## Scope
改动：三个构建文件（根 + pepper-lib-plugin + pepper-lib-shaded-example）的 compileOnly paper-api 版本；根 build.gradle.kts 新增 verifyCompileBaseline 守卫任务并接入 check；前置插件 paper-plugin.yml 的 api-version；README/文档中生态基线表述；TargetJvmVersion hack 注释更新（测试仍用 26.1 故保留）。不动：测试依赖、MockBukkit、业务/测试源码、CI。

## Acceptance criteria
1) 新增编译基线守卫任务（verifyCompileBaseline）断言 compileClasspath 的 paper-api 版本为 1.20.1，并纳入 check；先红（26.1 时失败）后绿；2) 三个构建文件 compileOnly 均为 paper-api 1.20.1-R0.1-SNAPSHOT，测试依赖保持 26.1（编译旧、测试新）；3) 前置插件 paper-plugin.yml api-version 从 26.1 降到 1.20；4) ./gradlew build 全绿（测试、spotless、javadoc、守卫、shadowJar）；5) 字节码守卫仍断言 major 61 且通过；6) README/文档中「API 26.1 基线」表述更新为 1.20.1。

## Failure modes
1) 主源码在 1.20.1 编译失败（新 API 误用）→ 已实验验证可行，若出现则报告具体文件并询问；2) 测试类路径 paper-api 1.20.1 与 26.1 冲突 → Gradle 取高版本 26.1，属预期（编译旧测试新），若解析异常则报告；3) 前置插件 api-version 1.20 与 Union/Claim 的 26.1 服务器兼容性 → 低版本 api-version 在新服务器总是被接受，无风险；4) japicmp/守卫因版本变化报错 → 属构建配置范畴，修复并复验。

## Priorities
正确性优先：绿门全绿是硬验收；编译基线守卫优先于一切（防回退到 26.1）；文档表述与实际基线一致；改动面最小化（只动必要文件）。

## Non-goals
不降测试依赖/MockBukkit（保持 26.1）；不降到 1.18.2；不换 plugin.yml 描述符（1.20.1 目标无需）；不加真实旧版 Paper 服务器 smoke（列为后续项）；不改 Union/Claim/BindManager/TrashBin；不发布新版本；不改 CI workflow。
