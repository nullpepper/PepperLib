plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
}

group = "ltd.pepper"
version = project(":").version

description = "PepperLib 可选 provider：纯内存实例世界服务实现（ltd.pepper.lib.world），插件 tmp + 符号链接隔离。"

dependencies {
    // 核心库仅编译期：运行时由 PepperLib 前置插件提供（类必须同 ClassLoader 才能经
    // ServicesManager 互通——本 provider 以「前置插件模式」为部署契约）。
    compileOnly(project(":"))
    compileOnly(libs.paper.api)

    testImplementation(project(":"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}

// ${version} 注入 plugin.yml（单一版本来源：根项目 version）。
tasks.processResources {
    inputs.property("version", project.version)
    expand("version" to project.version)
}

tasks.test {
    // 产物守卫测试（ProviderArtifactContentGuardTest）读取薄 jar。
    dependsOn(tasks.jar)
}
