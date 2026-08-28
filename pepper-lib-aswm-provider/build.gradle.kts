plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
}

group = "ltd.pepper"
version = project(":").version

description = "PepperLib 可选 provider：基于 Advanced Slime Paper API 的实例世界服务实现（ltd.pepper.lib.world）。"

repositories {
    maven {
        name = "infernalsuite"
        url = uri("https://repo.infernalsuite.com/repository/maven-releases/")
    }
    // ASP 文档指定：aswm-api 的传递依赖 flow-nbt 2.0.2 仅在此仓库发布。
    maven {
        name = "rapture"
        url = uri("https://repo.rapture.pw/repository/maven-releases/")
    }
}

dependencies {
    // 核心库仅编译期：运行时由 PepperLib 前置插件提供（类必须同 ClassLoader 才能经
    // ServicesManager 互通——本 provider 以「前置插件模式」为部署契约）。
    compileOnly(project(":"))
    compileOnly(libs.paper.api)
    // ASP API 仅编译期：运行时由 ASP 服务端/插件环境提供；不可用时插件自禁并降级。
    // 本地构建的 fork（vjh0107，支持 MC 26.1.2，包重命名 com.infernalsuite.asp.api）
    // 以文件依赖提供；flow-nbt 由 ASP 环境提供（本工程零 flow-nbt 代码）。
    compileOnly(files(libs.versions.aspApiLocal.get()))

    testImplementation(project(":"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(files(libs.versions.aspApiLocal.get()))
    // fork API 签名引用 adventure-nbt（BinaryTag/CompoundBinaryTag）；测试 Fake 需要。
    testImplementation("net.kyori:adventure-nbt:4.26.1")
    testImplementation(libs.flow.nbt)
    testImplementation(libs.mockito.core)
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
