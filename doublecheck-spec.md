# Doublecheck spec

## Goal
把 PepperUnion、PepperClaim 的 PepperLib 依赖升级到最新 0.5.0（构建坐标 + 运行时校验常量），构建验证全绿；确认 PepperBotBindManager 已是最新 0.5.0（零改动）；PepperBotCustomMessage 保持零改动；并产出 PepperLib 设计合理性评审。

## Scope
范围内：PepperUnion、PepperClaim 的 build.gradle.kts（compileOnly + testImplementation 的 io.pepper:pepper-lib:0.2.0→0.5.0）与主类 REQUIRED_PEPPERLIB_API（"0.2"→"0.5"）；PepperBotBindManager 仅核验（已 0.5.0，不改）；PepperBotCustomMessage 不动（用户明确「先不管他」，已撤销本会话误改）；PepperLib 仓库新增设计评审文档（docs/）；三个仓库（Union/Claim/PepperLib）提交。范围外：CustomMessage 前置模式接入（用户暂缓）；不提取任何新组件；不改 BindManager/ChatSync/CommandDispatcher/Core/Plugin 等其他插件；不发布 maven 产物、不部署服务器。

## Acceptance criteria
1) PepperUnion、PepperClaim 各自 ./gradlew test 全绿，依赖解析到 mavenLocal 的 io.pepper:pepper-lib:0.5.0；2) 两仓库 grep 无残留 pepper-lib 0.2.0 引用（构建文件/源码/文档）；3) REQUIRED_PEPPERLIB_API 与依赖版本一致（"0.5"），运行时 startsWith 校验语义自洽；4) PepperBotBindManager 三处（compileOnly/testImplementation/常量）确认 0.5.0 且 git log 有对应提交，零改动；5) PepperBotCustomMessage git status 干净（无本会话改动残留）；6) 设计评审文档写入 PepperLib docs/；7) Union/Claim/PepperLib 三个仓库提交完成。

## Failure modes
0.5.0 与 0.2.0 二进制不兼容导致编译失败：japicmp 门已保证 0.2→0.5 纯增量（0.3/0.4/0.5 各代对上一代兼容），若仍失败则报告具体 API 并回退依赖版本；运行时校验常量漏改导致服务器上 startsWith("0.2") 拒绝 0.5.0 而禁用插件：以 grep 全仓防漏（构建文件+源码+文档三处）；构建因环境失败（JDK 25 toolchain 缺失/网络不可达）：区分环境性失败与代码性失败，环境失败时报告而非误判代码；误改 CustomMessage：已 revert，以 git status 验证。

## Priorities
行为零回归与运行时版本一致性 > 构建全绿 > 提交完整性；评审文档深度服从事实与既有文档（adoption-decisions/双模式迁移文档为准），不做无依据的褒贬。

## Non-goals
用户明确暂缓：CustomMessage 前置模式接入（含删除本地副本/plugin.yml depend/onEnable 校验）；不动 BindManager 代码；不提取 Http/媒体/缓存等新组件；不发布 maven 产物、不部署。
