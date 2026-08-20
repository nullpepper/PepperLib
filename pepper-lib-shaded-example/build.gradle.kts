plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
    alias(libs.plugins.shadow)
}

group = "io.example"
version = "1.0.0"

description = "shade 模式示例消费者（双模式重构文档 §9.4）：relocate PepperLib 到私有命名空间，不安装前置插件。"

dependencies {
    // 以项目依赖模拟坐标消费者；shadow 时 relocate（shade 模式契约核心）。
    implementation(project(":"))
    compileOnly(libs.paper.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("ShadedExample")
    archiveClassifier.set("")
    // shade 模式核心约束：relocate 到私有命名空间，与前置插件/其他消费者互不冲突。
    relocate("io.pepper.lib", "io.example.shaded.lib")
}

tasks.test {
    // 产物守卫测试（ShadedJarContentGuardTest）读取 ShadedExample.jar。
    dependsOn(tasks.shadowJar)
}
