# 验证报告：Docker 容器 mc-peppercraft 真实配置 × 新版 PepperLib 配置管线（m24）

> 验证方式：把容器 `/data/plugins/<Plugin>` 的真实配置文件导出到 `/tmp/m24-verify`，用**新版**（工作树未提交构建）的插件编译类 + PepperLib **0.15.0** + snakeyaml 2.6 各自独立 classpath 真实装载（`ConfigFileStore.load` 走完整绑定，Claim 另跑 `StoreConfigSource` 全程管线含迁移/Fixups/组装）。容器内前置 PepperLib 为 **0.9.0**（旧），验证的是"新代码能否装载这些配置"，非部署。

## 结论：全部 6 个部署插件真实配置 = 0 ERROR / 0 WARN，正常加载

| 插件 | 文件 | errors/warns | 关键实证（全部来自真实文件） |
|---|---|---|---|
| PepperClaim | 9 个 | 0 / 0 | kebab `config-version: 1` → 内存迁移 `configVersion=1`（值保留）✓；金额换算 slots 2,560,000 分=25,600 元×100 ✓、economy 10,000/500 分 ✓；economy `distance-breakpoints` 末段 `to` 缺省 → `upTo=MAX`（1.797e308）✓；prosperity 通道/等级 id 派生自映射键 ✓（bronze=1…）；visuals showAll `radius=96` 继承 renderDistance、配色合并 ✓；worlds 继承 ✓；`StoreConfigSource` 全程管线 **OK**，diffLog 仅 4 组新键缺键回落 + 1 条版本键内存迁移提示（磁盘未改写） |
| PepperBotBindManager | config.yml | 0 / 0 | 真实 `type: mariadb`、host=mariadb、密码完整、`properties{useSSL, allowPublicKeyRetrieval}` → `useSSL=false&allowPublicKeyRetrieval=true` ✓、groups=[754966689]、mode=HYBRID、池 10/2 |
| PepperBotCustomMessage | config.yml | 0 / 0 | enabled-groups=[754966689, 522291896]、font='Minecraft AE'、http bind=0.0.0.0/port 8765、publicUrl=http://mc-peppercraft:8765、url-token 完整绑定 ✓ |
| PepperMinecart | config.yml | 0 / 0 | 含 post-load `resolve` 材质解析（无异常） |
| PepperUnion | config.yml | 0 / 0 | 绑定无告警 |
| GlowingSquad | config.yml | 0 / 0 | max-distance=128 绑定 ✓ |

## 完整性（不写回语义）

探针（新代码）运行后，导出目录与容器内所有 `.yml` 的 **md5 全部一致**，无任何 `.bak-v*` 生成——copy-once / 缺键不写回 / 不覆盖用户文件均成立。

## 部署前必读（非配置问题）

1. **前置 PepperLib 必须升级**：容器现为 `PepperLib-0.9.0.jar`，新版各插件运行期经 `pepperlib-api.properties` 要求 **≥ 0.15.0**（版本门不符会禁用插件）。部署新插件 jar 前需将前置换成 PepperLib **0.15.0**（本地 mavenLocal 有产物）。
2. 容器内各插件 jar 仍为旧构建（`.bak-0828` 时代）；本报告只证明**配置兼容**，未向容器部署任何新 jar。
3. CustomMessage 的 `templates.yml`/`rules/*.yml` 装载代码未动（spec 出范围），仍走 Bukkit YamlConfiguration，容器内无需迁移。

## 部署实机验证（2026-09-11，用户指令执行）

将 7 个新产物导入容器 `mc-peppercraft` 并 `docker restart` 后，启动日志确认：

- 前置 **PepperLib 0.15.0** 已启用（共享库前置）。
- 全部 6 个迁移插件启用成功，运行期版本门均输出 "PepperLib runtime 0.15.0 已就绪"：
  - PepperUnion：缺失 1 项（`activity.player-source-cap-overrides`，新键回落默认）、非法 0 项；Loaded 26 guild(s)
  - PepperClaim：9 文件装载 + **diffLog 与探针完全一致**（4 组缺键回落 + kebab `config-version→configVersion` 内存迁移提示，磁盘未改写）；Loaded 18 claim(s)；WorldGuard/PlaceholderAPI/linkage 均 hook 成功
  - PepperBotBindManager：HikariPool 用真实 mariadb 配置起池，`enabled (mode: HYBRID)`，绑定库正常
  - PepperBotCustomMessage：`重载完成: 7 条规则，跳过 0 条`
  - PepperMinecart：v1.0.0 已启用
  - GlowingSquad：`最大距离: 128`（真实配置）、联盟配置项注册
- 旧 lib 消费者二进制兼容：PepperPvpArena `已启用：PepperLib 0.15.0；world-instance READY`、PepperLib-ASWM-Provider 注册成功、PepperBotCore/ChatSync/CommandDispatcher 等全部启用。
- 全局 **0 ERROR / 0 SEVERE / 0 Exception / 0 Disabling**；服务器 Done。
- 旧 jar 全部备份为 `.bak-0911`（连同既有 `.bak-0828`），未动任何配置文件（md5 前验一致）。

## 方法备注

- 探针 `ConfigVerifyProbe`（/tmp/m24-verify）：每个插件独立 classpath（该插件 build/classes + build/resources + pepper-lib-0.15.0.jar + snakeyaml-2.6.jar；Minecart 用其自身测试运行时 classpath 以解析 `org.bukkit.Material`，Claim 无 Bukkit 依赖纯 JVM）。
- 输出的 `file=false` 是探针相对路径检查的假象（`fileName()` 相对 dataFolder）；模型取值（mariadb/密码/群号/字体等）证明读的是实体文件而非默认值。
- 全程未修改任何仓库源码；探针与导出数据在 /tmp/m24-verify（临时）。