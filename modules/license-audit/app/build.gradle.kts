import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

plugins {
    id("sollecitom.kotlin-library-conventions")
    application
}

dependencies {
    implementation(libs.org.json)
    implementation(libs.snakeyaml)

    runtimeOnly(libs.swissknife.logger.slf4j.adapter)
}

application {
    mainClass.set("sollecitom.tools.license_audit.app.LicenseAuditKt")
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.register<JavaExec>("selfCheck") {
    group = "verification"
    description = "Runs no-network assertions for the license audit policy engine."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("sollecitom.tools.license_audit.app.LicenseAuditSelfCheckKt")
}

tasks.named("check") {
    dependsOn("selfCheck")
}
