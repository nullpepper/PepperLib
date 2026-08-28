rootProject.name = "pepper-lib"

// 双模式重构（docs/pepperlib-dual-loading-and-consumer-migration.md §3）：
// 前置插件子项目产出 PepperLib.jar（Shadow 打包普通库、不 relocate）。
include("pepper-lib-plugin")
// 可选 provider：基于 Advanced Slime Paper API（com.infernalsuite.aswm:api:3.0.0）
// 的实例世界服务实现（ltd.pepper.lib.world 的 ServicesManager 提供者）。
// 独立薄 jar（非 PepperLib.jar 一部分）：核心库零 ASWM 引用，provider 单独分发。
include("pepper-lib-aswm-provider")
// shade 模式示例消费者（§9.4）：验证 relocate 契约与共存。
include("pepper-lib-shaded-example")
