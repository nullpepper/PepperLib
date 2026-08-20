plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
    `java-library`
    `maven-publish`
    alias(libs.plugins.japicmp)
}

group = "io.pepper"
version = "0.6.0"
description = "PepperLib - shared protocol, model and infrastructure primitives for PepperUnion and PepperClaim."

java {
    withSourcesJar()
    withJavadocJar()
}

// papermc + mavenCentral 由 pepper.java-conventions 提供；根项目额外仓库：
repositories {
    maven {
        name = "extendedclip"
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    // Paper API 仅编译期：PepperLib 不打包 Bukkit/Paper 类型，由插件运行时提供。
    compileOnly(libs.paper.api)
    compileOnly(libs.jetbrains.annotations)
    // PAPI 仅编译期软依赖（i18n PapiPlaceholderResolver / papi PapiExpansionSupport）：
    // 不打包、不传递；运行时由插件提供。
    compileOnly(libs.placeholderapi)
    // Vault 仅编译期软依赖（economy VaultSupport）：不打包、不传递；运行时由插件提供。
    compileOnly(libs.vault.api) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // 阶段 6.5 收敛：方言实现（SqliteDialect/MariaDbDialect）与 ConnectionPoolFactory
    // 已删除（零消费者），HikariCP 与驱动类字面量不再需要——消费方插件各自提供。

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
    // PAPI 测试同版本（与 Union 一致）：验证 jar 在场但未注册扩展的路径。
    testImplementation(libs.placeholderapi)
    // Vault 测试同版本（与 Union 一致）：ServicesManager 注册/解析路径。
    testImplementation(libs.vault.api) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // 迁移框架测试使用内存 SQLite（DriverManager 按 jdbc url 加载驱动）。
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    // 产物守卫测试（ArtifactContentGuardTest）读取 pepper-lib JAR。
    dependsOn(tasks.jar)
}

// javadoc 纳入绿门（check）：doclint reference error 直接阻断构建，防止文档腐化。
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            // artifactId 默认取 project.name（pepper-lib），坐标 io.pepper:pepper-lib:<version>。
        }
    }
    repositories {
        // 默认仅 mavenLocal（publishToMavenLocal）；设置 PEPPER_MAVEN_URL 时
        // 额外发布到内部仓库（凭据经环境变量注入，不落库）。
        val pepperMavenUrl = providers.environmentVariable("PEPPER_MAVEN_URL").orNull
        if (pepperMavenUrl != null) {
            maven {
                name = "pepper-internal"
                url = uri(pepperMavenUrl)
                credentials {
                    username = providers.environmentVariable("PEPPER_MAVEN_USER").orNull
                    password = providers.environmentVariable("PEPPER_MAVEN_TOKEN").orNull
                }
            }
        }
    }
}

// 二进制兼容门（发布面，§10）：基线 = 上一发布版本坐标（默认 mavenLocal 的 0.5.0，
// 可用环境变量 PEPPER_LIB_BASELINE_JAR 覆盖；发布新版本后更新默认值）。
// 已纳入 check（绿门）；基线 jar 缺失时跳过并告警（fresh 环境/CI 无本地发布历史）——
// 接入远程发布后，CI 经 PEPPER_LIB_BASELINE_JAR 提供上一版本产物即自动生效。
val japicmpBaseline =
    providers.environmentVariable("PEPPER_LIB_BASELINE_JAR")
        .map(::file)
        .orElse(file("${System.getProperty("user.home")}/.m2/repository/io/pepper/pepper-lib/0.5.0/pepper-lib-0.5.0.jar"))

val japicmp = tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("japicmp") {
    group = "verification"
    description = "与上一发布版本（0.5.0）做二进制兼容性比较；破坏性变更即失败。"
    oldClasspath = files(japicmpBaseline)
    newClasspath = files(tasks.jar)
    // 外部依赖类型（Paper/PAPI/Vault 为 compileOnly）不参与比较；只分析库自身 API。
    ignoreMissingClasses = true
    onlyModified = true
    failOnModification = false // 允许新增 API（0.2.x 政策）
    // failOnBinaryIncompatibleModification 默认 true：删除/签名变更即失败。
    onlyIf {
        val baseline = japicmpBaseline.get()
        if (!baseline.exists()) {
            logger.warn(
                    "japicmp: 基线 jar 不存在（" + baseline + "），跳过二进制兼容比较；"
                            + "本地发布上一版本后即自动生效，或设置 PEPPER_LIB_BASELINE_JAR 指向上一版本产物。")
            false
        } else {
            true
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("japicmp"))
}
