# 实例世界能力（world-instance）设计记录

> 日期：2026-08-25。版本：PepperLib 0.7.0（Experimental）。
> 关联：README「实例世界能力」章节、CHANGELOG 0.7.0。

## 1. 背景与动机

PVP 竞技场与 PVE 竞技场（两个独立插件）都需要「同一张地图的隔离实例」：
比赛/副本开始 → 从 Slime 模板创建独立世界 → 传送玩家 → 结束 → 清场 → 卸载丢弃。
两插件语义一致（已确认），构成 PepperLib 提取纪律的「双消费者」条件；
因两者代码均未先存在，本能力按**共设计（co-design）+ Experimental 状态**进入
（转正条件：两插件接入并上线）。

## 2. 架构决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 核心/实现解耦 | 核心 SPI（`ltd.pepper.lib.world`）+ 独立 provider 子项目 | 核心零 ASWM 引用（依赖方向守卫）；实现可替换 |
| Slime 实现锁定 | `com.infernalsuite.aswm:api:3.0.0`（Advanced Slime Paper API） | 调研确认：InfernalSuite 官方项目已从插件转为 **ASP 服务端 fork**（官方 README 明示）；该 API 是唯一公开发布产物（repo.infernalsuite.com，javap 验证 38 个公共类型） |
| 部署形态 | provider 运行时探测 `AdvancedSlimePaperAPI.instance()`，不可用则自禁降级 | 服务器部署（ASP fork vs 旧插件线）是运营决策；provider 以探测兼容两种环境，绝不因 API 缺失拖垮 PepperLib 与其他插件 |
| 注册/发现 | Bukkit ServicesManager（与 `PepperLibRuntime` 同构） | 消费者 `getRegistration(InstanceWorldService.class)`，无 provider 即 `PROVIDER_UNAVAILABLE` |
| 能力声明 | `@MinMinecraftVersion("1.18", "world-instance")` + `PepperLibRuntime.CAP_WORLD_INSTANCE` | 与 gui-host 同机制；「API 存在」与「provider 可用」是两层 |
| 失败模型 | `WorldProviderException` + 8 个稳定错误码 | 调用方按码分支，不解析异常文本；provider 内部细节进日志与 `WorldProviderInfo` |
| 线程模型 | 模板读取/解析异步（`readWorld`），世界注册/卸载主线程（`PepperScheduler`） | 大型 Slime 文件不在主线程解析；Bukkit 世界操作强制主线程 |
| 卸载语义 | `UnloadOptions`：业务 `discardWhenEmpty`（非空拒卸）/ 关闭 `discardForShutdown`（强制+记录） | 修正早期设计缺陷：关闭清理若照搬 requireEmpty 必然失败（插件 disable 时序） |
| 防落盘 | readOnly=true + `unloadWorld(save=false)` + `setAutoSave(false)` 三重 | 实例修改只在内存，永不写回模板 |

## 3. 已验证的 API 事实（2026-08-21）

- `com.infernalsuite.aswm:api:3.0.0`（唯一 release 版本；maven-metadata 确认）。
- 核心流程（javap 验证 + 官方 `LoadTemplateWorldCmd` 源码佐证）：
  - `AdvancedSlimePaperAPI.instance()` → `readWorld(loader, name, readOnly, props)`
    （异步）→ `slimeWorld.clone(instanceName)` → `loadWorld(clone, readOnly)`
    （主线程）→ `Bukkit.getWorld(name)`；
  - `SlimeWorld.clone(name)` 即「模板一份、每实例独立活体世界」的官方路径，
    与用户原始诉求（只在内存加载一份地图、复用、实例隔离）完全对应；
  - 异常族：`UnknownWorldException` / `CorruptedWorldException` /
    `NewerFormatException` / `IOException` → 映射 `TEMPLATE_ERROR`。
- 依赖事实：aswm-api POM 传递依赖 flow-nbt 2.0.2，仅发布于已失效的 rapture 仓库
  （502）；本工程排除传递依赖，以 Maven Central 的 flow-nbt 1.0.0 满足 javac 签名
  解析（工程零 flow-nbt 代码；运行时由 ASP 环境提供）。若后续 rapture 恢复或
  ASP 发布新 API 版本，可移除该例外。

## 4. 公共 API（0.7.0）

```text
ltd.pepper.lib.world
├── InstanceWorldService        # SPI：create/find/instances/unload/unloadAll
├── WorldTemplateRef            # record(id, source 绝对路径)；id 限 [a-z0-9_-]+
├── WorldInstanceRequest        # record(template, instanceId)
├── WorldInstance               # instanceId/worldName/templateId/world()/state()
├── WorldInstanceState          # LOADING/ACTIVE/UNLOADING/UNLOADED/FAILED
├── UnloadOptions               # discardWhenEmpty()/discardForShutdown()
├── WorldProviderInfo           # provider 诊断（id/版本/范围）
├── WorldProviderException      # RuntimeException + error()
├── WorldProviderError          # 8 码（见下）
└── WorldIdRules                # 包私有校验
```

错误码（8，数量有测试守卫）：`PROVIDER_UNAVAILABLE` / `INVALID_REQUEST` /
`TEMPLATE_ERROR` / `INSTANCE_ID_CONFLICT` / `WORLD_LOAD_FAILED` /
`WORLD_NOT_EMPTY` / `WORLD_UNLOAD_FAILED` / `SERVICE_CLOSED`。

## 5. provider 行为要点（pepper-lib-aswm-provider）

- 插件：`PepperLib-ASWM-Provider`，`depend: [PepperLib]`；薄 jar（无 shadow）。
- `onEnable`：探测 `AdvancedSlimePaperAPI.instance()` → 成功注册
  `InstanceWorldService`（`ServicePriority.Normal`）；失败自禁 + 双行诊断日志。
- `onDisable`：`close()` 主线程同步卸载全部实例（不依赖调度器——disable 期间
  调度器不可用），逐实例记录失败，`unregisterAll`。
- 模板加载：`TemplateFileLoader`（单文件只读 `SlimeLoader`，save/delete 抛
  UnsupportedOperationException）；`readTemplate` 文件校验 + 解析 + 按模板 id 缓存
  `SlimeWorld`（内存一份）。
- 创建：`registry.putIfAbsent` 占位（冲突即拒绝）→ 异步读模板 → 主线程
  clone+loadWorld+autoSave 关闭+ACTIVE；任何失败 `whenComplete` 清理
  （移除记录 + 尽力卸载半成品世界）。
- 卸载：`save=true` 拒绝；未知 id 幂等成功；`requireEmpty` 校验
  `world.getPlayers()`；`unloadWorld(world, false)` 失败 → `WORLD_UNLOAD_FAILED`
  且实例保留。
- 世界名：`pepper_inst_<uuid32>`，调用方不可指定（防冲突/注入/旧世界误载）。

## 6. 测试

- 核心（纯 JDK，5 类）：模板/请求校验、UnloadOptions 语义、异常错误码、
  能力注解守卫。
- provider（MockBukkit + 假 ASP API，11 项）：创建成功与可查询、模板缺失无残留、
  ID 冲突、非空拒卸（实例与世界保留）、空世界卸载、save=true 拒绝、未知 id
  幂等、关闭后拒绝、unloadAll、providerInfo、SPI 类型契约。
  - 假 API：`readWorld` 只校验 loader 存在性、`loadWorld` 用 MockBukkit 真实建世界，
    使生命周期端到端可测；
  - 注意：MockBukkit 异步任务在独立线程执行，测试须轮询驱动 tick
    （`await` 辅助），真实 Paper 主线程持续 tick 无此问题。
- 待补（真实环境）：Paper + ASP smoke——同模板两实例隔离、A 改块不影响 B、
  卸载不写回模板、无 ASP 时插件自禁降级。

## 7. 限制与后续

- 仅前置插件模式（shade 消费者自携 provider）。
- v1 不保存实例（save=true 拒绝）；无 reset（两消费者确认不需要）；
  无并发上限（量级个位数~十几个）；模板缓存无热更新（显式 invalidate 后续加）。
- 竞技场插件锁步接入（本工作区外）；上线后转正冻结。
- 若 InfernalSuite 发布新 API 版本或 rapture 恢复，重估 flow-nbt 例外与 API 锁定。
