plugins {
    java
    id("com.diffplug.spotless") version "8.9.0"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "io.pepper"
version = project(":").version

description = "PepperLib 前置插件：服务器单一实例提供未 relocate 的 io.pepper.lib.* 类。"

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
    // 普通库整体打入前置插件，但不 relocate（前置插件模式契约）。
    implementation(project(":"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.115.0")
}

spotless {
    java {
        importOrder("\\#", "")
        palantirJavaFormat("2.97.0")
        target("src/**/*.java")
    }
}

// 薄 jar 不生成：插件产物以 shadowJar（PepperLib.jar）为准。
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("PepperLib")
    archiveClassifier.set("")
    // 前置插件模式核心约束：不 relocate，服务器提供未 relocate 的 io.pepper.lib.*。
    // 与 shade 模式消费者（各自 relocate 到私有命名空间）互不冲突。
}

// ${version} 注入 paper-plugin.yml（单一版本来源：根项目 version）。
tasks.processResources {
    expand("version" to project.version)
}

tasks.test {
    useJUnitPlatform()
    // 产物守卫测试（PluginArtifactContentGuardTest）读取 PepperLib.jar。
    dependsOn(tasks.shadowJar)
}
