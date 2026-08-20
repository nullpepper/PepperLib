plugins {
    java
    id("com.diffplug.spotless") version "8.9.0"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "io.example"
version = "1.0.0"

description = "shade 模式示例消费者（双模式重构文档 §9.4）：relocate PepperLib 到私有命名空间，不安装前置插件。"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
}

dependencies {
    // 以项目依赖模拟坐标消费者；shadow 时 relocate（shade 模式契约核心）。
    implementation(project(":"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        importOrder("\\#", "")
        palantirJavaFormat("2.97.0")
        target("src/**/*.java")
    }
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
    useJUnitPlatform()
    // 产物守卫测试（ShadedJarContentGuardTest）读取 ShadedExample.jar。
    dependsOn(tasks.shadowJar)
}
