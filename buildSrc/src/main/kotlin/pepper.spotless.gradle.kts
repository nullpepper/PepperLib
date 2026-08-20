// 约定插件：Spotless 格式（与 PepperClaim / PepperUnion 保持一致：
// 静态导入优先，随后单一字母序块）。
plugins {
    id("com.diffplug.spotless")
}

spotless {
    java {
        importOrder("\\#", "")
        // buildSrc 预编译插件编译期拿不到主构建的版本目录访问器（Gradle 9 限制），
        // 此处为唯一硬编码点，升级时须与 gradle/libs.versions.toml 的
        // palantirJavaFormat 保持同步（buildSrc 自身构建脚本可用 libs，这里不行）。
        palantirJavaFormat("2.97.0")
        target("src/**/*.java")
    }
}
