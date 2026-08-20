// buildSrc 是独立构建，默认看不到根项目的版本目录；
// 这里显式加载根目录的 gradle/libs.versions.toml（单一来源，buildSrc 与主构建共用）。
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
