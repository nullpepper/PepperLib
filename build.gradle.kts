plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.9.0"
    // 二进制兼容门：0.2.x 允许新增 API，不允许删除/签名变更（japicmp 基线见下）。
    id("me.champeau.gradle.japicmp") version "0.4.5"
}

group = "io.pepper"
version = "0.2.0"
description = "PepperLib - shared protocol, model and infrastructure primitives for PepperUnion and PepperClaim."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "extendedclip"
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
    mavenCentral()
}

dependencies {
    // Paper API 仅编译期：PepperLib 不打包 Bukkit/Paper 类型，由插件运行时提供。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    compileOnly("org.jetbrains:annotations:26.0.1")
    // PAPI 仅编译期软依赖（i18n PapiPlaceholderResolver / papi PapiExpansionSupport）：
    // 不打包、不传递；运行时由插件提供。
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Vault 仅编译期软依赖（economy VaultSupport）：不打包、不传递；运行时由插件提供。
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // 阶段 6.5 收敛：方言实现（SqliteDialect/MariaDbDialect）与 ConnectionPoolFactory
    // 已删除（零消费者），HikariCP 与驱动类字面量不再需要——消费方插件各自提供。

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.115.0")
    // PAPI 测试同版本（与 Union 一致）：验证 jar 在场但未注册扩展的路径。
    testImplementation("me.clip:placeholderapi:2.11.6")
    // Vault 测试同版本（与 Union 一致）：ServicesManager 注册/解析路径。
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // 迁移框架测试使用内存 SQLite（DriverManager 按 jdbc url 加载驱动）。
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}

spotless {
    java {
        // 与 PepperClaim / PepperUnion 保持一致：静态导入优先，随后单一字母序块。
        importOrder("\\#", "")
        palantirJavaFormat("2.97.0")
        target("src/**/*.java")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
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

// 二进制兼容门（发布面，§10）：基线 = 上一发布版本坐标（默认 mavenLocal 的 0.1.0，
// 可用环境变量 PEPPER_LIB_BASELINE_JAR 覆盖）。0.2.x 允许新增 API，禁止删除/
// 签名变更（0.1.0 之后发布的版本逐步接入 CI）。
val japicmpBaseline =
    providers.environmentVariable("PEPPER_LIB_BASELINE_JAR")
        .orElse("${System.getProperty("user.home")}/.m2/repository/io/pepper/pepper-lib/0.1.0/pepper-lib-0.1.0.jar")

val japicmp = tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("japicmp") {
    group = "verification"
    description = "与上一发布版本（0.1.0）做二进制兼容性比较；破坏性变更即失败。"
    oldClasspath = files(japicmpBaseline)
    newClasspath = files(tasks.jar)
    // 外部依赖类型（Paper/PAPI/Vault 为 compileOnly）不参与比较；只分析库自身 API。
    ignoreMissingClasses = true
    onlyModified = true
    failOnModification = false // 允许新增 API（0.2.x 政策）
    // failOnBinaryIncompatibleModification 默认 true：删除/签名变更即失败。
}
