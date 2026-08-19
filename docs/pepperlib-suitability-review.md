# PepperLib 通用性审查报告

> 审查日期：2026-08-20。范围：PepperLib v0.1.0（commit eb6dbaf）是否适合作为通用库存在——
> 分别评估「两插件内部共享库」与「对外发布的通用库」两种定位。

## 1. 事实盘点

### 1.1 API 面（20 个公共类型，5 个模块）

| 模块 | 类型 |
|---|---|
| validation | `Preconditions` |
| gui | `PageWindow` `Pagination` `GuiEventGuards` `GuiClick` `GuiSessionId` `GuiPage` `GuiContext` `GuiItemFactory` |
| task | `PepperScheduler` |
| storage | `Migration` `MigrationRunner` `SqlDialect` `SqliteDialect` `MariaDbDialect` `StorageException` `ConnectionPoolFactory` |
| confirm | `ConfirmEntry` `ConfirmRegistry` `ConfirmCleanupListener` |

### 1.2 消费者矩阵（两插件 main 源码实际引用文件数）

| 状态 | API |
|---|---|
| **有消费者（9）** | `SqlDialect`(13) `Migration`(12) `PageWindow`(2) `Pagination`(2) `GuiEventGuards`(2) `GuiClick`(2) `MigrationRunner`(2) `GuiSessionId`(1) |
| **零消费者（11）** | `Preconditions` `GuiPage` `GuiContext` `GuiItemFactory` `PepperScheduler` `ConfirmEntry` `ConfirmRegistry` `ConfirmCleanupListener` `StorageException` `SqliteDialect` `MariaDbDialect` `ConnectionPoolFactory` |

### 1.3 依赖方向与构建面

- ✅ 零插件引用（无 `io.pepper.claim` / `io.pepper.union` import）
- ✅ Paper API 仅 compileOnly；sqlite/mariadb 驱动仅 compileOnly（运行时由消费方提供）
- ⚠️ `HikariCP` 为唯一 `api` 依赖——只因 `ConnectionPoolFactory` 返回 `HikariDataSource`（该工厂本身零消费者）
- ⚠️ Java 25 toolchain（绑定 Paper 26.1.2 生态；GraalVM 25 本机路径写死在 gradle.properties）
- ⚠️ 未发布：无 Maven 坐标落地、无 publish 配置；两插件经 composite build 本地接线

### 1.4 其他

- 测试：110 个全绿（纯模型 + Mockito + SQLite 真实库），质量扎实
- 文档：类级 Javadoc 齐全；**无 README、无 docs/**、无 API 稳定性声明
- 命名：`PepperScheduler` 带 Pepper 前缀，其余类型不带（不一致）

## 2. 六维评估

| 维度 | 评分 | 说明 |
|---|---|---|
| 依赖方向 | ✅ | 干净：不依赖插件、不依赖 Bukkit 运行时（compileOnly 合规） |
| 去重价值 | ✅ | 分页/守卫/迁移框架确实被两插件使用，且已删除插件侧重复实现（Claim 536/Union 305 测试保持绿） |
| API 验证度 | ❌ | **55%（11/20）的公共 API 零消费者**——未经验证的前置抽象 |
| 生态绑定 | ⚠️ | Java 25 + Paper 26.1.2 强绑定：作为插件家族共享库合理，作为对外通用库过强 |
| 发布面 | ❌ | 无发布流程、无版本策略落地、composite build 依赖本机路径 |
| 文档/命名 | ⚠️ | Javadoc 好但缺 README/稳定性声明；前缀不一致 |

## 3. 结论

### 作为两插件内部共享库：✅ 适合

价值已被真实消费者验证（两插件各删除了重复的分页、守卫、迁移框架实现且测试全绿），
依赖方向干净，测试扎实。**当前定位应明确为「Pepper 插件家族内部共享库」。**

### 作为对外发布的通用库：❌ 暂不适合

三个阻断项：

1. **55% API 未经验证**——`GuiPage`/`GuiContext`/`GuiItemFactory`/`ConfirmRegistry` 三件套/
   `PepperScheduler`/`ConnectionPoolFactory`/`Preconditions`/lib 方言实现均无消费者。
   对外发布意味着把未经验证的 API 面变成兼容性负担（违背计划 §7「不提前创建」的代价已经显现）。
2. **发布面缺失**——无 Maven 发布、无独立版本策略、composite build 绑定本机路径，外部使用者无法消费。
3. **生态绑定过强**——Java 25 + Paper 26.1.2 快照坐标，脱离两插件场景即失去意义。

### 死代码确认

- `SqliteDialect`/`MariaDbDialect`（lib 版）：两插件各自用自己的方言实现 lib `SqlDialect` 接口，
  从不 new lib 方言——**lib 方言实现是纯死代码**（仅 lib 自身测试使用）。
- `ConnectionPoolFactory`：零消费者，且是 `HikariCP` api 依赖的唯一理由。

## 4. 建议（按优先级）

1. **定位声明**：补 README，明确「Pepper 插件家族内部共享库，v0.1.0，API 未稳定」。
2. **收敛死代码**（建议下一轮做）：
   - 删除 `ConnectionPoolFactory` + `HikariCP` 降为 compileOnly（或移除）——无消费者、拖依赖面；
   - 删除 lib 方言实现（`SqliteDialect`/`MariaDbDialect`）——无消费者；`SqlDialect` 接口保留（有消费者）；
   - `StorageException` 定位为 lib 内部异常（文档说明，不承诺跨边界）。
3. **未验证 API 标注**：`GuiPage`/`GuiContext`/`GuiItemFactory`/`Confirm*`/`PepperScheduler`/
   `Preconditions` 加 `@Experimental` 注解 + Javadoc 标注「等待两插件接入验证」——
   计划阶段 4（Scheduler）、阶段 6（Union GuiPage 适配）、confirm 接入均明确规划，保留但标记。
4. **发布面**：等两插件全部接入（阶段 4/6/7）后，再补 maven-publish 与版本策略（计划 §8 已有方案）。
5. **命名**：小修——`PepperScheduler` 前缀与其他类型不一致，可后续统一（不阻塞）。

## 5. 一句话结论

**PepperLib 作为两插件内部共享库已成立且健康；作为对外通用库需先完成「消费者全部接入 + 死代码收敛 +
发布面补齐」三步，当前不满足。**
