import sollecitom.plugins.RepositoryConfiguration

buildscript {
    repositories {
        mavenLocal()
    }

    dependencies {
        classpath(libs.sollecitom.gradle.plugins)
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
    gradlePluginPortal()
    RepositoryConfiguration.BuildScript.apply(this)
}