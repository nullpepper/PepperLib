// 约定插件：Java 编译基线（toolchain 25 / --release 17）、依赖解析 JVM 属性钉回、
// 公共仓库与 JUnit 5 测试配置。三个子项目共享，消除 build.gradle.kts 重复。
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Java 17 字节码基线（2025-08 评估结论）：解锁纯 Java 模块（storage/money/validation）
    // 在 Java 17 运行时（如 PepperBotBindManager 的 Spigot 生态）的坐标/shade 消费。
    // toolchain 保持 25，javac --release 17 同时守卫后续误用 17+ API/特性（编译期即失败）。
    options.release.set(17)
}

// Gradle 会按 --release 推断依赖解析的目标 JVM 版本（17），但编译期依赖
// paper-api 26.1 的模块元数据只兼容 JVM 25。把解析属性钉回 toolchain 版本：
// 字节码仍由 --release 17 控制，仅依赖解析按 25 匹配。
// 发布变体（apiElements/runtimeElements）除外——它们按真实字节码 17 声明，
// 否则 Java 17 消费者（如 PepperBotBindManager 的 Spigot 生态）无法解析坐标。
configurations.configureEach {
    if (name == "apiElements" || name == "runtimeElements") {
        return@configureEach
    }
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
        java.toolchain.languageVersion.get().asInt())
}

// 发布元数据按实际字节码基线（--release 17）声明：Java 17+ 消费者均可解析。
configurations.apiElements.configure {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}
configurations.runtimeElements.configure {
    attributes.attribute(
        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}
