rootProject.name = "pepper-lib"

// 双模式重构（docs/pepperlib-dual-loading-and-consumer-migration.md §3）：
// 前置插件子项目产出 PepperLib.jar（Shadow 打包普通库、不 relocate）。
include("pepper-lib-plugin")
// shade 模式示例消费者（§9.4）：验证 relocate 契约与共存。
include("pepper-lib-shaded-example")
