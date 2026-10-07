plugins {
    id("sollecitom.kotlin-library-conventions")
}

dependencies {
    api(libs.swissknife.logger.core)

    implementation(libs.swissknife.kotlin.extensions)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
