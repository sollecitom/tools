dependencies {
    implementation(projects.toolsPackageMigratorDomain)
    implementation(projects.swissknifeKotlinExtensions)

    runtimeOnly(projects.swissknifeLoggerSlf4jAdapter)
}