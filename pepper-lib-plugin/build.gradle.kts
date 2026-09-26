plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
    alias(libs.plugins.shadow)
}

group = "ltd.pepper"
version = project(":").version

description = "PepperLib 前置插件：服务器单一实例提供未 relocate 的 ltd.pepper.lib.* 类。"

dependencies {
    // 普通库整体打入前置插件，但不 relocate（前置插件模式契约）。
    implementation(project(":"))
    compileOnly(libs.paper.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}

// 薄 jar 不生成：插件产物以 shadowJar（PepperLib.jar）为准。
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("PepperLib")
    archiveClassifier.set("")
    // 前置插件模式核心约束：不 relocate，服务器提供未 relocate 的 ltd.pepper.lib.*。
    // 与 shade 模式消费者（各自 relocate 到私有命名空间）互不冲突。
}

// ${version} 注入 plugin.yml（单一版本来源：根项目 version）。
// inputs.property 显式声明：project.version 变化必须使 processResources 失效
// （否则 Gradle UP-TO-DATE 误判，发布新版本时产物残留旧版本号）。
tasks.processResources {
    inputs.property("version", project.version)
    expand("version" to project.version)
}

tasks.test {
    // 产物守卫测试（PluginArtifactContentGuardTest）读取 PepperLib.jar。
    dependsOn(tasks.shadowJar)
    // 守卫按当前版本定位产物：硬编码版本号会随发版漂移，且只有旧产物残留在 build/libs
    // 时才“通过”（clean build 必红）。版本经系统属性注入，测试缺失即失败。
    systemProperty("pepperLibVersion", project.version.toString())
}
