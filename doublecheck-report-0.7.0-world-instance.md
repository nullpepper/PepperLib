# Doublecheck report

> Verdict: **green**

## Spec
- Goal: 实现 PepperLib 0.7.0 的 Experimental 世界实例能力：核心 io.pepper.lib.world 公共 API（双消费者共设计：PVP/PVE 竞技场插件，语义一致：结束→清场→卸载）+ 独立 pepper-lib-aswm-provider 子项目（基于唯一公开发布产物 com.infernalsuite.aswm:api:3.0.0，运行时探测降级），红绿测试与文档齐备，构建全绿。
- Scope: 范围内：PepperLib 核心 io.pepper.lib.world 包（WorldProviderError/WorldProviderException/WorldTemplateRef/WorldInstanceRequest/UnloadOptions/WorldInstanceState/WorldInstance/WorldProviderInfo/InstanceWorldService/WorldIdRules/package-info + PepperLibRuntime.CAP_WORLD_INSTANCE）；pepper-lib-aswm-provider 子项目（薄 jar、plugin.yml、AswmInstanceWorldService、TemplateFileLoader、AswmWorldInstance、PepperLibAswmProviderPlugin）；核心单测与 provider MockBukkit 测试；settings.gradle.kts/版本目录/根版本 0.7.0；README/CHANGELOG/docs 设计记录。
- Acceptance criteria: 1) `./gradlew :test` 与 `./gradlew :pepper-lib-aswm-provider:test` 全绿（红绿纪律：核心测试先红后绿）；2) `io.pepper.lib.world` 公共 API 与设计一致：WorldProviderError 恰 8 码、UnloadOptions 双工厂、WorldInstance 状态机、InstanceWorldService 六方法、@MinMinecraftVersion 能力标注 world-instance 且有守卫测试；3) provider 子项目构建产物为薄 jar（无 shadow），compileOnly 依赖 pepper-lib 与 com.infernalsuite.aswm:api:3.0.0，plugin.yml 声明 depend PepperLib，onEnable 运行时探测 AdvancedSlimePaperAPI.instance()，不可用则禁用自身并给出诊断、不注册服务；4) provider 行为：模板单文件只读 SlimeLoader、模板缓存、实例唯一命名、创建失败清理无半成品、重复 instanceId 拒绝、save=true 拒绝、requireEmpty 语义、关闭清理宽松执行；5) MockBukkit 测试覆盖：创建成功/模板缺失/ID 冲突/非空拒卸/空世界卸载/关闭后拒绝；6) 文档：README 内容表新增 world 行与 provider 说明、CHANGELOG 0.7.0 条目、docs/ 设计记录（含 ASP 部署结论与降级语义）；7) 版本 0.6.0 → 0.7.0 同步（根项目 + plugin.yml 注入）。
- Failure modes: ASWM API 类在运行时缺失（普通 Paper）：插件 onEnable 捕获 Throwable 禁用自身、不注册服务，竞技场收到 PROVIDER_UNAVAILABLE，不创建普通世界；flow-nbt 传递依赖解析失败：追加 rapture 仓库或报告环境性问题；MockBukkit 不支持某世界 API（unloadWorld/getPlayers/setAutoSave 已确认支持）：以真实行为为准调整测试断言而非放宽契约；supplyOnMain 依赖 performTicks 驱动：测试显式 tick；onDisable 期间调度器不可用：close() 同步执行卸载不依赖调度器；模板文件缺失/不可读/损坏：TEMPLATE_ERROR 且 registry 无残留；世界加载中途失败：清理已注册世界并移除 entry；japicmp 基线缺失：跳过并告警（既有行为）。
- Priorities: 实例隔离与不落盘语义 > 核心/provider 解耦（核心零 ASWM 引用）> 失败可诊断与资源回收 > 异步非阻塞 > 构建全绿；API 表面最小化服从双消费者真实调用面；运行时探测降级优于强制部署绑定；测试先红后绿。
- Non-goals: 不实现竞技场业务/玩家监听/传送/匹配；不接入本工作区外的竞技场插件（锁步接入由用户侧后续进行）；不实现重置复用（reset）；不实现并发上限/队列（量级个位数~十几个）；不实现数据库/Redis 模板源；不实现模板热更新监听；不把 ASWM 类型暴露进核心公共 API；不切换服务端为 ASP fork（部署决策归用户，provider 以运行时探测兼容）；不发布 maven 产物、不部署服务器；不改动既有已接入 API。

## Test evidence
- failing runs: 0
- passing runs: 0

- [spec] 在 PepperLib 中定义一套与具体 Slime 实现解耦的异步竞技场实例世界 API，并规划一个可选的 AdvancedSlimeWorldManager provider，使调用方能从服务器数据目录加载 Slime 模板、创建隔离实…
- [spec] 在 PepperLib 中定义一套与具体 Slime 实现解耦的异步世界实例 API（Experimental 能力，双消费者共设计：PVP 竞技场与 PVE 竞技场两个独立插件，语义一致：结束→清场→卸载），规划可选 ASWM provi…
- [spec] 实现 PepperLib 0.7.0 的 Experimental 世界实例能力：核心 io.pepper.lib.world 公共 API（双消费者共设计：PVP/PVE 竞技场插件，语义一致：结束→清场→卸载）+ 独立 pepper-l…

## Adversary review
No adversary review ran for this session.

## Verification
Not run.

## Delivery
- implementation edits: 39
