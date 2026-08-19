plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.9.0"
}

group = "io.pepper"
version = "0.1.0"
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
    mavenCentral()
}

dependencies {
    // Paper API 仅编译期：PepperLib 不打包 Bukkit/Paper 类型，由插件运行时提供。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    compileOnly("org.jetbrains:annotations:26.0.1")
    // 方言实现引用驱动类字面量（shading 重定位友好）；驱动本体由插件提供。
    compileOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    // ConnectionPoolFactory 返回 HikariDataSource：消费方需要该类型，作为 api 暴露。
    api("com.zaxxer:HikariCP:5.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")
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
}

// javadoc 纳入绿门（check）：doclint reference error 直接阻断构建，防止文档腐化。
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}
