# Doublecheck spec

## Goal
从 PepperBotBindManager 提取两块通用能力进 PepperLib 0.4.0：① 新增 io.pepper.lib.verification.OneTimeCodeService&lt;T&gt;（一次性验证码服务：issue/consume 原子消费/冷却/TTL 清理/设置热替换）；② io.pepper.lib.storage 新增纯 JDK 的 SqlExceptions（唯一冲突/繁忙分类）与 JdbcRetry（有限退避重试）；BindManager 同步改造为委托（VerificationManager 内部委托 OneTimeCodeService，BindManagerImpl 改用 SqlExceptions/JdbcRetry），PepperLib 版本升 0.4.0 且 BindManager 依赖与 REQUIRED_PEPPERLIB_API 同步改 0.4，两仓库构建与测试全绿。

## Scope
PepperLib：新增 io.pepper.lib.verification 包（OneTimeCodeService&lt;T&gt;、内部 CodeGenerator/Cooldown 实现、VerificationSettings 等价设置记录）；storage 包新增 SqlExceptions、JdbcRetry；版本 0.3.0→0.4.0（artifact + apiVersion 同步）；japicmp baseline 0.2.0 不变；新增/随迁测试（含一次性消费与冷却并发断言）；CHANGELOG 或文档提及。BindManager：VerificationManager 改造为委托壳（全部公共方法签名与行为不变：createGameInitiatedRequest/createQQInitiatedRequest/createWhitelistRequest/matchCode/restoreCode/getPendingForPlayer/removePendingForPlayer/isOnCooldown/getCooldownRemainingSeconds/cleanupExpired）；BindManagerImpl 用 SqlExceptions/JdbcRetry 替换私有 isUniqueViolation/isBusyViolation/withConnectionRetry；依赖 io.pepper:pepper-lib 0.3.0→0.4.0；REQUIRED_PEPPERLIB_API 0.3→0.4；测试随迁与保留。JdbcPool/HikariCP 不进库（维持阶段 6.5 边界），DatabaseManager 池逻辑不改；不提取 PendingBindManager/生命周期/命令/监听器；不改 pepperbot-api/core 契约。

## Acceptance criteria
1) PepperLib `./gradlew clean check` BUILD SUCCESSFUL（新增 OneTimeCodeService/SqlExceptions/JdbcRetry 测试全部通过，japicmp 无破坏性 diff）；2) `./gradlew publishToMavenLocal` 产出 io.pepper:pepper-lib:0.4.0，apiElements/runtimeElements 仍声明 org.gradle.jvm.version=17；3) BindManager `./gradlew clean build` 绿（21+ 测试 0 失败），shadow jar 内 io/pepper/lib 计数=0、不再包含 com.zaxxer.hikari（BindManager 侧池代码未动则不强制）；4) VerificationManager 全部原测试（含 BindManagerImplAsyncTest/ConflictTest/GuardTest 与 VerificationManagerConcurrencyTest）在改造后仍绿；OneTimeCodeService 在 PepperLib 侧有等价并发断言（同码并发只成功一次、冷却原子性、过期清理、restore 语义由 BindManager 测试覆盖）；5) BindManager build.gradle 依赖 0.4.0 且 REQUIRED_PEPPERLIB_API="0.4"。

## Failure modes
泛型化导致行为回归（冷却原子性/一次性消费/过期清理/白名单码复用）→ 由随迁与保留测试红绿证明；JdbcPool 未提取故无 hikari 类加载问题；BindManager 在 0.3 runtime 上启动（校验 0.4 前缀不匹配）→ 现有 verifyPepperLibRuntime 逻辑输出 severe 并 disablePlugin；japicmp 新增包/类视为兼容，若配置报错则调整 onlyIf 或忽略规则；spotless 格式失败 → spotlessApply 后重跑；mavenLocal 缓存旧 0.3.0 → publishToMavenLocal --rerun-tasks 覆盖。

## Priorities
行为零回归 > API 简洁（OneTimeCodeService 泛型单类职责）> 测试随迁完整性；JdbcPool 提取明确不做（rule of three，等待第二个消费者）；文档更新为次要但 CHANGELOG 建议补一条；VerificationManager 对外签名不变优先于内部实现优雅度。

## Non-goals
不把 HikariCP/连接池工厂带回 PepperLib（不逆转阶段 6.5）；不提取 PendingBindManager、插件生命周期、命令/监听器、GroupMessageListener；不改 pepperbot-api/pepperbot-core 任何契约；不改 VerificationManager/BindManagerImpl 公共签名；不做真实服务器 smoke（验收=构建+测试）；不迁移 Messages 等已交付功能；不改 DatabaseManager 的池配置逻辑。
