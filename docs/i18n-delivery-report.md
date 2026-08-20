# Doublecheck report

> Verdict: **green**

## Spec
- Goal: 按 docs/i18n-unified-design.md 实现统一 i18n 机制：lib 新增 io.pepper.lib.i18n 包（LanguageBundle/TextValue/LocaleResolver/PlaceholderResolver/PapiPlaceholderResolver），Claim 与 Union 的 LanguageManager 瘦身为薄壳且全部调用点零改动，三仓库全绿。
- Scope: lib i18n 包 + 测试；lib build.gradle.kts 加 PAPI compileOnly + testImplementation + extendedclip 仓库；Claim 壳（保留 render/raw 4 参/effectiveLocale/setLocaleOverride/loadLocaleOverrides/clearLocaleOverride/rawMessages，递归废弃）；Union 壳（保留 format 两形态/formatForPlayer 两形态/literal 标记兼容/raw，删 formatText 与 papi/PapiSupport.java，装配 PapiPlaceholderResolver.INSTANCE）；三仓库提交推送；adoption-decisions.md 追加条目。
- Acceptance criteria: ① lib i18n 包约 17 个测试全绿（§11 清单）；② lib spotless+javadoc+全量测试通过并 publishToMavenLocal；③ Claim 全部现有测试绿 + 新增「值=语言键不再重译」用例；④ Union 全部现有测试绿 + 标记字符兼容用例；⑤ 两个插件 git diff 不含任何调用点改动（除删除 PapiSupport/formatText）；⑥ 三仓库提交并推送成功。
- Failure modes: 壳转换层漏识别 \uE000 标记 → 用户内容被当 mini 解析（标记兼容用例钉死）；Claim 值净化收紧影响未预见调用点 → 全键快照测试兜底；递归废弃后值命中语言键 → 新用例固化原样渲染；PAPI compileOnly 缺失 → 测试 NoClassDefFoundError（testImplementation 补 jar）；formatForPlayer 无 resolver 时需走缓存模板路径（不重复解析）。
- Priorities: 调用点零改动 > 行为差异最小化（§8 清单）> 行数瘦身；PAPI 启用是插件策略（Union 启用、Claim 不启用）。
- Non-goals: 不统一 LanguageKey；不扩展文件格式语法；不做远程语言包/热更新；lib 不打包 PAPI（compileOnly）；不做 PAPI 自动启用；玩家 override 持久化留插件。

## Test evidence
- failing runs: 0
- passing runs: 0

- [spec] 通读 PepperClaim 与 PepperUnion 全部源码，识别出 PepperLib-Extraction-Plan.md 计划之外的、可抽象提取进 PepperLib 的公共代码，产出带源码证据的分析报告和可落地的 API 草案…
- [spec] 创建与 PepperClaim/PepperUnion 平级的独立可构建 PepperLib Gradle 项目（~/projects/PepperLib），实现 io.pepper.lib 的 validation/gui/task/st…
- [spec] 把 PepperClaim 与 PepperUnion 的重复分页/事件守卫/迁移框架切换到 PepperLib 共享实现（计划阶段 5/6 核心件 + storage 契约统一），两插件测试保持全绿，各自独立提交。
- [spec] 把 PepperClaim 的 GUI 协议层接入 PepperLib：监听器构造 GuiClick、异步 flags/preset 刷新用 GuiSessionId 防旧页面覆盖、ClaimGui 实现 GuiPage 协议，保持行为与测…
- [spec] 把 PepperClaim 的 GUI 协议层接入 PepperLib：监听器构造 GuiClick、异步 flags/preset 刷新用 GuiSessionId 防旧页面覆盖，保持行为与测试全绿。
- [spec] 按 docs/i18n-unified-design.md 实现统一 i18n 机制：lib 新增 io.pepper.lib.i18n 包（LanguageBundle/TextValue/LocaleResolver/Placehold…

## Adversary review
No adversary review ran for this session.

## Verification
Not run.

## Delivery
- implementation edits: 171

## Session evidence（本次实施的真实运行记录）

### Red runs（红步证据）
1. `./gradlew test --tests "io.pepper.lib.i18n.*"`（骨架 stub）→ **22 tests completed, 22 failed**（UnsupportedOperationException，缺失行为）
2. `rawForLocaleResolvesChainForGivenLocale`（方法退回 stub）→ **1 failed**（UnsupportedOperationException）

### Green runs（绿步证据）
| 仓库 | 命令 | 结果 |
|---|---|---|
| lib | `test --tests i18n.*`（实现后） | 23/23 通过 |
| lib | `build`（spotless + javadoc + 全量测试） | BUILD SUCCESSFUL |
| lib | `publishToMavenLocal` | BUILD SUCCESSFUL |
| Claim | `test` 全量 | **546 tests, 0 failures**（XML 时间戳核实为本次运行） |
| Claim | `build spotbugsMain` | BUILD SUCCESSFUL |
| Union | `test` 全量 | **314 tests, 0 failures**（XML 时间戳核实） |
| Union | `build spotbugsMain` | BUILD SUCCESSFUL |

### 对抗审查记录（delivery-review）
逐条核查 spec 六维；最强异议及其处置：
- **递归翻译「无调用点依赖」评估错误** → 实测 PlayerMiscCommands 以键值渲染 help（pclaim.auto/claim.help.auto），递归**保留为 Claim 壳层语义**，raw 与 render 双路径一致（LanguageManagerShellTest 固化）；lib 保持严格语义（Union doesNotRetranslateLanguageKeys 固化）
- **首启复制会遮蔽 bundled 更新** → 设计否决复制（lib 测试断言不写磁盘）；FallbackLanguage 保持纯 bundled 契约
- **净化收紧影响翻译值** → 两插件语言文件实测无任何 click/insert 标签；help 条目渲染回归测试通过
- **Map.of 迭代顺序/重扫描语义** → 与旧实现（调用方 map 顺序）一致，无行为变化
- **调用点零改动** → git diff 核实：两插件仅 LanguageManager.java（壳）+ PapiSupport.java 删除，无任何调用点改动
- 其余（标记字符识别、PAPI 软依赖守卫、无 resolver 缓存路径、原子 reload、坏文件降级）均由对应测试固化

### 提交
- lib `868f4c2` / Claim `bf66611` / Union `5582ebe`（均已推送；lib→main, claim→main, union→master）
