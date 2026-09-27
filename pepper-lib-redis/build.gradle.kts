plugins {
    id("pepper.java-conventions")
    id("pepper.spotless")
    `java-library`
    `maven-publish`
}

group = "ltd.pepper"
version = project(":").version

description = "PepperLib 平台中立 Redis 客户端：纯 JDK 实现，支持 PING / SET / GET / DEL / PUBLISH / SUBSCRIBE。"

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "pepper-lib-redis"
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
