plugins {
    id("sollecitom.kotlin-library-conventions")
    application
}

dependencies {
    implementation(libs.org.json)
    implementation(libs.snakeyaml)

    runtimeOnly(libs.swissknife.logger.slf4j.adapter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("sollecitom.tools.license_audit.app.LicenseAuditKt")
}
