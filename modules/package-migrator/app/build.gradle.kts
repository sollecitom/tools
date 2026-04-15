plugins {
    id("sollecitom.kotlin-library-conventions")
}

dependencies {
    implementation(projects.toolsPackageMigratorDomain)
    implementation(libs.swissknife.kotlin.extensions)

    runtimeOnly(libs.swissknife.logger.slf4j.adapter)
}
