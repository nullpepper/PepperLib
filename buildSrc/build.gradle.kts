plugins {
    `kotlin-dsl`
}

repositories {
    // 预编译约定插件需要的外部插件（spotless）从这里解析。
    gradlePluginPortal()
}

dependencies {
    // pepper.spotless 约定插件在 plugins {} 中无版本地应用 com.diffplug.spotless：
    // 版本经根项目版本目录单一来源（libs.versions.spotless）。
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:${libs.versions.spotless.get()}")
}
