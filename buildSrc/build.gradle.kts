import sollecitom.plugins.RepositoryConfiguration

buildscript {
    dependencies {
        classpath("sollecitom.gradle-plugins", "gradle-plugins")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    `kotlin-dsl`
    alias(libs.plugins.com.github.ben.manes.versions)
    alias(libs.plugins.nl.littlerobots.version.catalog.update)
    alias(libs.plugins.com.palantir.git.version)
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    RepositoryConfiguration.BuildScript.apply(this)
}