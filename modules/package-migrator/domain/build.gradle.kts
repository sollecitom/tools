plugins {
    id("sollecitom.kotlin-library-conventions")
}

dependencies {
    api(libs.swissknife.logger.core)

    implementation(libs.swissknife.kotlin.extensions)
}
