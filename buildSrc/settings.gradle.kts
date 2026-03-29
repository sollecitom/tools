rootProject.name = "tools"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

includeBuild("../../gradle-plugins")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")