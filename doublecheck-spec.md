# Doublecheck spec

## Goal
将上一轮交付的 M1/M2/M5/M6/M7/L1/L2/L3 修复方案全部实施：lib 侧改动（ThreadGuard 实例化、reload 同步、tryParse 上限、失败日志、Preconditions 转正、package-info、CHANGELOG、javadoc 死链、CI 冒烟）并同步迁移 PepperClaim/PepperUnion 的 ThreadGuard 调用点，三仓库验证通过。

## Scope
PepperLib 仓库：task/ThreadGuard.java 实例化改造（保留 deprecated 静态委托壳）、i18n/LanguageBundle.java reload 同步、money/Amounts.java tryParse 长度上限、papi/PapiExpansionSupport.java 失败日志、validation/Preconditions.java 去 Experimental、9 个包 package-info.java、README.md 定位与 API 表格、CHANGELOG.md 新建、.github/workflows/ci.yml 消费者冒烟 job、全部相关测试更新。PepperClaim 与 PepperUnion 仓库：ThreadGuard 静态调用点迁移为实例注入（组合根字段 + 静态访问器），其余不动。

## Acceptance criteria
① lib `./gradlew check` 全绿（162 tests，spotless/javadoc 过）；② M1：ThreadGuard 实例 API + 静态 deprecated 委托壳，ThreadGuardTest 覆盖实例隔离与静态壳委托，两插件全部调用点迁移且各自 build 通过；③ M7：reload synchronized + 并发回归测试绿；④ L1：tryParse 长度上限 + 测试绿；⑤ L2：注册失败输出 warning；⑥ M5：Preconditions 转正 + README 同步；⑦ M2：9 个 package-info + README 生态边界行；⑧ M6：CHANGELOG、javadoc 无仓库路径引用（grep 清零）、CI 消费者冒烟 job；japicmp 因无发布基线记录为「0.1.0 发布后接入」；⑨ L3：ConfirmRegistry 登记前惰性清扫 + 测试绿。

## Failure modes
① ThreadGuard 静态壳与实例并存 → 壳仅作 deprecated 委托到进程级默认实例，测试覆盖两者语义一致；② 插件坐标依赖 0.1.0 → 先 publishToMavenLocal 再构建插件；③ 插件构建失败 → 记录原因并修复至编译通过（本任务必须三仓库全绿）；④ L2 日志不可断言 → 代码审查验收；⑤ javadoc gate 对 package-info 格式要求 → 按规范；⑥ 并发测试脆弱 → 回归保护测试 + synchronized 语义审查。

## Priorities
三仓库编译/测试全绿优先；M1 兼容壳优先于彻底删除静态 API（0.1.x 政策）；跨仓库改动面最小化（调用点机械替换，不改业务逻辑）。

## Non-goals
不发布到 Maven（mavenLocal 之外）；不接入 japicmp（无基线，待 0.1.0 发布后）；不做插件侧定时清扫 op（采用 register 惰性清扫）；不改两插件业务代码；不做性能优化。
