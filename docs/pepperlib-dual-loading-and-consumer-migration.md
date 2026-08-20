# PepperLib 双模式加载与消费者迁移方案

## 1. 目标与范围

PepperLib 同时支持两种运行模式：

1. **前置插件模式**：服务器只安装一份 `PepperLib.jar`，消费者通过 Paper 插件依赖使用未 relocate 的 `io.pepper.lib.*` 类。
2. **shade 模式**：消费者将普通 `pepper-lib` 库打入自己的 JAR，并 relocate 到消费者私有命名空间；服务器不需要安装 PepperLib 前置插件。

PepperClaim 和 PepperUnion 固定迁移到前置插件模式。第三方消费者可以按部署场景选择任一模式。

本方案的核心约束是：**同一个消费者发行包只能选择一种模式**。不能在同一个 JAR 中同时携带未 relocate 的 PepperLib 类，又尝试使用服务器上的 PepperLib 前置插件。

## 2. 当前状态与问题

当前 PepperLib 是普通 `java-library` 项目，消费者使用 `compileOnly` 坐标依赖。项目本身没有 `paper-plugin.yml`、Paper 插件主类或服务器服务注册入口。

PepperClaim 和 PepperUnion 当前也将 PepperLib 声明为 `compileOnly`，且不进行 shade。这种配置只有在服务器另外提供 PepperLib 类时才成立，但当前仓库没有提供可被 Paper 加载的 PepperLib 前置插件产物。

因此，迁移前必须补充一个独立的前置插件产物，并且通过 Paper 的 `dependencies.server` 声明加载顺序和必需关系。CI 也必须从“消费者编译通过”扩展到“完整 Paper 服务器可以启动”。

## 3. 目标产物与工程结构

建议保留当前根项目作为普通库，并新增一个插件子项目：

```text
PepperLib/
├─ src/main/java/io/pepper/lib/       # 普通库 API 与实现
├─ src/test/java/                     # 普通库测试
├─ build.gradle.kts                   # pepper-lib
└─ pepper-lib-plugin/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/java/io/pepper/lib/plugin/PepperLibPlugin.java
      └─ main/resources/paper-plugin.yml
```

### 3.1 `pepper-lib` 普通库

普通库继续使用 `java-library` 和 `maven-publish`：

- 坐标：`io.pepper:pepper-lib:<version>`
- 包含 `io.pepper.lib.*` 类、sources JAR 和 Javadoc JAR
- 不包含 `paper-plugin.yml`
- 不包含 `JavaPlugin` 主类
- 不包含插件生命周期逻辑
- Paper、PlaceholderAPI、Vault 保持 `compileOnly`

普通库 JAR 不能被直接放入服务器 `plugins/` 目录。它是编译和 shade 输入，不是可加载插件。

### 3.2 `pepper-lib-plugin` 前置插件

插件子项目使用 `implementation(project(":"))` 引用普通库，并通过 Shadow 打包普通库类，但不 relocate：

```kotlin
plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("PepperLib")
    archiveClassifier.set("")
}
```

插件描述文件：

```yaml
name: PepperLib
version: '${version}'
main: io.pepper.lib.plugin.PepperLibPlugin
api-version: '26.1'
load: STARTUP
folia-supported: false
```

最终服务器插件产物应为 `PepperLib.jar`，其中包含：

- `paper-plugin.yml`
- `io.pepper.lib.plugin.PepperLibPlugin`
- 未 relocate 的 `io.pepper.lib.*`

## 4. 前置插件运行时设计

`PepperLibPlugin` 只负责共享库运行时的初始化和服务注册：

```java
public final class PepperLibPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // 注册 PepperLibRuntime 等稳定服务
    }

    @Override
    public void onDisable() {
        // 注销服务并清理插件自身资源
    }
}
```

建议在普通库中新增一个最小的稳定运行时接口，例如：

```java
public interface PepperLibRuntime {
    String apiVersion();

    boolean supports(String capability);
}
```

前置插件通过 `ServicesManager` 注册该接口。消费者可以用它完成版本和能力诊断，但不应依赖插件主类的具体实现。

前置插件不拥有消费者业务状态：

- `GuiHost` 由每个消费者自行创建，并使用消费者自己的插件实例注册事件。
- `LanguageBundle`、`MigrationRunner` 和 `ConfirmRegistry` 由每个消费者自行创建。
- `ThreadGuard.Instance` 继续由每个消费者独立持有。
- 不恢复全局静态状态共享。
- PlaceholderAPI 和 Vault 仍然是可选能力，PepperLib 前置插件在它们缺失时必须能够启动。

## 5. 前置模式下的消费者契约

PepperClaim 和 PepperUnion 的 Gradle 依赖保持为：

```kotlin
compileOnly("io.pepper:pepper-lib:<version>")
```

它们不得将 PepperLib 放进 `shadowJar`。

两个消费者的 `paper-plugin.yml` 都增加：

```yaml
dependencies:
  server:
    PepperLib:
      load: BEFORE
      required: true
```

现有的 PepperUnion、PlaceholderAPI、Vault、TrChat 等依赖继续保留，按各自的可选性声明。

目标加载顺序：

```text
PepperLib
    ↓
PepperUnion
    ↓
PepperClaim
```

PepperClaim 仍可通过自己的其他依赖使用 PepperUnion；PepperLib 是两个消费者共同的必需前置，不替代 PepperUnion 的业务依赖关系。

## 6. Shade 模式契约

第三方消费者使用普通库坐标：

```kotlin
implementation("io.pepper:pepper-lib:<version>")
```

并在 Shadow 配置中 relocate：

```kotlin
tasks.shadowJar {
    relocate("io.pepper.lib", "com.example.myplugin.lib.pepper")
}
```

shade 模式的消费者 JAR 必须满足：

- 包含 relocate 后的 PepperLib 类。
- 不包含原始 `io.pepper.lib.*` 类。
- 不包含 `paper-plugin.yml`。
- 不包含 `PepperLibPlugin` 主类。
- 不声明必需的 PepperLib 前置依赖。

未 relocate 的 PepperLib 类不能直接嵌入消费者。否则它会与服务器前置插件或其他消费者产生类身份冲突，导致 `ClassCastException`、服务类型不匹配或静态状态重复。

## 7. PepperClaim 迁移方案

### 阶段 C1：编译依赖切换

1. 将 PepperLib 版本统一到目标版本，例如 `0.2.0`。
2. 保持 `compileOnly("io.pepper:pepper-lib:0.2.0")`。
3. 保持 `testImplementation("io.pepper:pepper-lib:0.2.0")`。
4. 确认 `shadowJar` 没有包含 `io/pepper/lib/**`。

### 阶段 C2：Paper 依赖声明

在 `PepperClaim/src/main/resources/paper-plugin.yml` 的 `dependencies.server` 中增加：

```yaml
PepperLib:
  load: BEFORE
  required: true
```

不把 PepperLib 写成软依赖。缺少 PepperLib 时，PepperClaim 不具备运行条件，应由 Paper 阻止加载。

### 阶段 C3：启动与版本诊断

在 `onEnable` 的早期阶段：

1. 获取 `PepperLibRuntime` 服务。
2. 验证最低 API 版本和必需能力。
3. 失败时输出包含实际版本、要求版本和安装建议的错误日志。
4. 不在 PepperClaim 中反射加载 PepperLib 私有实现。

### 阶段 C4：运行验证

使用 `PepperLib.jar + PepperClaim.jar` 启动 Paper，确认：

- 插件顺序正确。
- GUI、确认菜单、i18n、PAPI 和 Vault 路径正常。
- 数据库迁移仍由 PepperClaim 自己管理。
- 玩家退出、插件禁用和 GUI 关闭不会留下 PepperLib 全局状态。

## 8. PepperUnion 迁移方案

### 阶段 U1：编译依赖切换

1. 将 PepperLib 版本统一到目标版本，例如 `0.2.0`。
2. 保持 `compileOnly("io.pepper:pepper-lib:0.2.0")`。
3. 保持 `testImplementation("io.pepper:pepper-lib:0.2.0")`。
4. 确认 `shadowJar` 不包含 `io/pepper/lib/**`。

### 阶段 U2：Paper 依赖声明

在 `PepperUnion/src/main/resources/paper-plugin.yml` 的 `dependencies.server` 中增加：

```yaml
PepperLib:
  load: BEFORE
  required: true
```

PlaceholderAPI、Vault、TrChat 继续保持现有软依赖语义，不要把这些可选插件提升为 PepperLib 的硬依赖。

### 阶段 U3：启动与版本诊断

在 `onEnable` 早期获取并校验 `PepperLibRuntime`。ThreadGuard、ConfirmRegistry、Scheduler 和 GUI 仍由 PepperUnion 自己创建实例，不通过静态全局入口共享状态。

### 阶段 U4：运行验证

使用 `PepperLib.jar + PepperUnion.jar` 启动 Paper，确认：

- PepperUnion 能在 PepperLib 之后正常加载。
- PlaceholderAPI、Vault 缺失时 PepperUnion 仍按软依赖策略启动。
- GUI 和确认菜单事件正常。
- 数据库连接、迁移和 MariaDB/SQLite 路径不受影响。

## 9. 测试矩阵

### 9.1 普通库产物测试

- `pepper-lib` JAR 不包含 `paper-plugin.yml`。
- `pepper-lib` JAR 不包含 `PepperLibPlugin`。
- `pepper-lib` JAR 包含全部公开 `io.pepper.lib.*` 类。
- POM、Gradle metadata、sources JAR 和 Javadoc JAR 坐标正确。

### 9.2 前置插件测试

- `PepperLib.jar` 包含插件描述文件和主类。
- 仅安装 Paper 时 PepperLib 可以启动。
- 服务注册和注销行为正确。
- PlaceholderAPI、Vault 缺失时不阻断启动。
- 版本和能力信息可以被消费者读取。
- 插件禁用后不遗留任务、服务或监听器。

### 9.3 PepperClaim/PepperUnion 前置模式测试

- 安装 `PepperLib + PepperUnion + PepperClaim` 时三者全部启动。
- PepperLib 先于两个消费者加载。
- 两个消费者使用同一份未 relocate 的 PepperLib 类。
- 两个消费者可以同时创建独立的 GUI、确认注册表和 ThreadGuard 实例。
- 缺少 PepperLib 时消费者被 Paper 拒绝加载。
- PepperLib API 版本过低时消费者输出明确错误。
- 实际 Paper 服务器启动测试通过，不能只做 Gradle 编译测试。

### 9.4 Shade 模式测试

- 示例消费者 shade 后包含 relocate 后的 PepperLib 类。
- shade JAR 不包含 `paper-plugin.yml` 和原始 `io.pepper.lib.*`。
- 未安装 PepperLib 前置时 shade 消费者可以独立启动。
- shade 消费者与 PepperLib 前置插件同时存在时不冲突。
- 一个服务器同时运行前置模式消费者和 shade 模式消费者时无类型冲突。
- 两个 shade 消费者使用不同 PepperLib 版本时互不污染。

## 10. CI 与发布

现有 CI 的消费者 smoke test 只验证构建。应拆成以下任务：

```text
library-check
plugin-artifact-check
thin-consumer-paper-smoke
shaded-consumer-paper-smoke
```

发布产物：

- `io.pepper:pepper-lib:<version>`：普通库。
- `io.pepper:pepper-lib-plugin:<version>` 或独立下载的 `PepperLib.jar`：前置插件。

版本号应统一来源于 Gradle 属性，并注入 `paper-plugin.yml`。发布前必须完成：

1. 普通库测试和 Javadoc 检查。
2. 前置插件 JAR 内容检查。
3. PepperClaim/PepperUnion 前置模式服务器启动测试。
4. shade 示例消费者服务器启动测试。
5. 二进制兼容性检查。
6. 生成 changelog 和迁移说明。

## 11. 部署和回滚

### 前置模式服务器

```text
plugins/
├─ PepperLib.jar
├─ PepperUnion.jar
└─ PepperClaim.jar
```

不要把普通 `pepper-lib-*.jar` 放入 `plugins/`。

### Shade 模式服务器

```text
plugins/
└─ ThirdPartyPlugin.jar
```

不需要安装 PepperLib 前置插件。

迁移期间允许一台服务器同时存在：

- PepperUnion 前置模式
- PepperClaim 前置模式
- 第三方 shade 模式消费者

但同一个消费者不能同时安装 thin JAR 和 shade JAR。升级 PepperLib 前置插件前，应先验证消费者的最低 API 版本；发生不兼容时回滚前置插件和消费者到匹配版本。

## 12. 验收标准

方案完成的必要条件：

1. PepperLib 前置插件可以在干净 Paper 服务器中启动。
2. PepperClaim 和 PepperUnion 声明 PepperLib 为必需前置，并以 thin JAR 正常启动。
3. 两个消费者的最终 JAR 不包含 PepperLib 类。
4. shade 示例消费者可以在没有前置插件的服务器中运行。
5. shade 消费者和前置模式消费者可以共存。
6. 缺失前置、版本不兼容、可选依赖缺失时都有可诊断失败行为。
7. CI 覆盖构建、产物检查和至少一次真实 Paper 启动矩阵。

