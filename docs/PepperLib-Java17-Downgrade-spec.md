# Doublecheck spec

## Goal
PepperLib 全仓（根项目 + pepper-lib-plugin + pepper-lib-shaded-example）构建目标降为 Java 17 字节码（toolchain 保持 25、options.release=17），./gradlew build 绿门全绿，javap 核验主源码编译产物 major version = 61，并在聊天中给出运行时语义结论（Java 17 运行时消费边界、BindManager 接入路径、对 Union/Claim 无影响）。

## Scope
改动仅限 PepperLib 仓库的构建配置（根 build.gradle.kts 及两个子项目 build.gradle.kts 的 JavaCompile release 设置与必要的构建兼容调整）；运行 ./gradlew build 全量验证；不修改任何业务/测试源码；不涉及 Union/Claim/BindManager/TrashBin/Minecart；不改 CI workflow（除非绿门暴露问题）；不执行 publish。

## Acceptance criteria
1) 静态扫描确认主+测试源码无 Java 17 以上语言特性与 JDK API（record/instanceof pattern/switch 箭头等 ≤16 特性允许）；2) ./gradlew build 通过（含测试、spotless、javadoc、产物守卫、子项目 shadowJar）；3) javap 核验主源码类 major version = 61（Java 17）；4) 子项目同样以 release 17 编译且构建通过；5) 聊天结论覆盖运行时语义：纯 Java 模块（storage/money/validation）可在 Java 17 运行时消费（shade 或坐标依赖），Bukkit 耦合模块仍需 Paper 26.1 生态，前置插件 api-version 26.1 无法在旧 Paper 加载，对现有消费者 Union/Claim（Java 25 运行时）无回归。

## Failure modes
1) 编译失败源于 17+ API/特性用法 → 列出具体文件与行、给出替代方案，停下询问，不擅自改写（用户已选定此策略）；2) 构建环境问题（JDK 25 缺失、Gradle/toolchain 解析失败）→ 报告环境问题与解决建议；3) spotless/javadoc/japicmp/产物守卫因 release 17 报错 → 属构建配置范畴，按错误修复构建配置并复验，若涉及守卫断言字节码版本则报告；4) 子项目编译依赖 lib 产物 → 按依赖顺序构建，失败即报告。

## Priorities
正确性优先：绿门全绿是硬验收，任何一项红即视为未完成；落地降级是主目标，运行时冒烟（Java 17 真实加载）本次不做（用户验收选择了编译+字节码核验）；评估报告文档不写（用户明确不需要）；发现 17+ 用法只报告不改写优先于「让构建通过」。

## Non-goals
不改写任何发现 17+ 用法的代码；不降 toolchain（保持 Java 25 编译，仅降字节码目标）；不做 Java 17 运行时冒烟测试；不发布新版本（不跑 publish）；不写评估报告 md；不接入 BindManager/任何消费者；不修改 CI workflow；不动 Union/Claim/TrashBin/Minecart 任何文件。
