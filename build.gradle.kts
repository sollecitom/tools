import sollecitom.plugins.conventions.task.dependency.version.MinimumDependencyVersion
import sollecitom.plugins.conventions.task.dependency.version.MinimumDependencyVersionConventions
import sollecitom.plugins.conventions.task.kotlin.KotlinTaskConventions
import sollecitom.plugins.conventions.task.maven.publish.MavenPublishConvention
import sollecitom.plugins.conventions.task.test.AggregateTestMetricsConventions
import sollecitom.plugins.conventions.task.test.TestTaskConventions
import sollecitom.plugins.RepositoryConfiguration
import sollecitom.plugins.conventions.task.dependency.update.DependencyUpdateConvention
import com.palantir.gradle.gitversion.GitVersionPlugin
import com.palantir.gradle.gitversion.VersionDetails
import groovy.lang.Closure
import sollecitom.plugins.Plugins
import sollecitom.plugins.ProjectSettings
import java.nio.file.Path
import java.nio.file.Paths

buildscript {
    dependencies {
        classpath("sollecitom.gradle-plugins", "gradle-plugins")
    }
}

repositories {
    RepositoryConfiguration.BuildScript.apply(this)
}

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    `java-library`
    idea
    alias(libs.plugins.com.palantir.git.version)
}

apply<GitVersionPlugin>()
apply<DependencyUpdateConvention>()

val parentProject = this
val currentVersion: String by project
val versionDetails: Closure<VersionDetails> by extra
val gitVersion = versionDetails()
val libsFolder: Path = rootProject.projectDir.path.let { Paths.get(it) }.resolve("libs")
val servicesFolder: Path = rootProject.projectDir.path.let { Paths.get(it) }.resolve("services")
val toolsFolder: Path = rootProject.projectDir.path.let { Paths.get(it) }.resolve("tools")
val resourceFolder: Path = rootProject.projectDir.path.let { Paths.get(it) }.resolve("resources")

fun Project.isLibrary() = projectDir.path.let { Paths.get(it) }.startsWith(libsFolder)

apply<AggregateTestMetricsConventions>()

allprojects {

    project.extra["gitVersion"] = gitVersion

    group = ProjectSettings.groupId
    version = currentVersion

    repositories { RepositoryConfiguration.Modules.apply(this, project) }

    apply<IdeaPlugin>()
    idea { module { inheritOutputDirs = true } }

    apply<KotlinTaskConventions>()
    apply<TestTaskConventions>()

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    if (isLibrary()) {
        apply<MavenPublishConvention>()
    }

    java(Plugins.JavaPlugin::configure)

    apply<MinimumDependencyVersionConventions>()
    configure<MinimumDependencyVersionConventions.Extension> {
        val apacheCommonsCompress = MinimumDependencyVersion(group = "org.apache.commons", name = "commons-compress", minimumVersion = "1.26.0")
        knownVulnerableDependencies.set(
            listOf(
                apacheCommonsCompress
            )
        )
    }

    subprojects {
        apply {
            plugin("org.jetbrains.kotlin.jvm")
            plugin<JavaLibraryPlugin>()
        }
    }
}

val containerBasedServiceTest: Task = tasks.register("containerBasedServiceTest").get()