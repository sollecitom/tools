package sollecitom.plugins.conventions.task.dependency.update

import com.github.benmanes.gradle.versions.VersionsPlugin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import nl.littlerobots.vcu.plugin.KeepConfiguration
import nl.littlerobots.vcu.plugin.PinConfiguration
import nl.littlerobots.vcu.plugin.VersionCatalogConfig
import nl.littlerobots.vcu.plugin.VersionCatalogUpdateExtension
import nl.littlerobots.vcu.plugin.VersionCatalogUpdatePlugin
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.withType

abstract class DependencyUpdateConvention : Plugin<Project> {

    override fun apply(project: Project) = with(project) {

        pluginManager.apply(VersionCatalogUpdatePlugin::class)
        pluginManager.apply(VersionsPlugin::class)
        val extension = project.extensions.create<Extension>("versionCatalog")

        afterEvaluate {
            tasks.withType<DependencyUpdatesTask> {
                checkConstraints = extension.check.constraints.getOrElse(defaultCheckConstraints)
                checkBuildEnvironmentConstraints = extension.check.buildEnvironmentConstraints.getOrElse(defaultCheckBuildEnvironmentConstraints)
                checkForGradleUpdate = extension.check.forGradleUpdate.getOrElse(defaultCheckForGradleUpdate)
                extension.gradle.releaseChannel.takeIf { it.isPresent }?.let { gradleReleaseChannel = it.get() }
                extension.gradle.versionsApiBaseUrl.takeIf { it.isPresent }?.let { gradleVersionsApiBaseUrl = it.get() }
                outputFormatter = extension.report.formats.getOrElse(defaultReportFormats)
                outputDir = extension.report.outputDirectory.getOrElse(defaultOutputDirectory)
                reportfileName = extension.report.fileName.getOrElse(defaultReportFileName)

                rejectVersionIf {
                    wouldDowngradeVersion() || wouldDestabilizeAStableVersion()
                }
            }

            extensions.configure<VersionCatalogUpdateExtension> {
                sortByKey.set(extension.sortByKey.getOrElse(defaultSortByKey))
                extension.catalogFile.takeIf { it.isPresent }?.let {
                    catalogFile.set(it.get())
                }
                keep {
                    keepUnusedVersions.set(extension.keep.keepUnusedVersions.getOrElse(defaultKeepUnusedVersions))
                    keepUnusedLibraries.set(extension.keep.keepUnusedLibraries.getOrElse(defaultKeepUnusedLibraries))
                    keepUnusedPlugins.set(extension.keep.keepUnusedPlugins.getOrElse(defaultKeepUnusedPlugins))
                    versions.set(extension.keep.versions.getOrElse(emptySet()))
                    groups.set(extension.keep.groups.getOrElse(emptySet()))
                    libraries.set(extension.keep.libraries.getOrElse(emptySet()))
                    plugins.set(extension.keep.plugins.getOrElse(emptySet()))
                }
                pin {
                    versions.set(extension.pins.versions.getOrElse(emptySet()))
                    groups.set(extension.pins.groups.getOrElse(emptySet()))
                    libraries.set(extension.pins.libraries.getOrElse(emptySet()))
                    plugins.set(extension.pins.plugins.getOrElse(emptySet()))
                }
            }
        }
    }

    private companion object {
        const val defaultSortByKey = false
        const val defaultKeepUnusedVersions = true
        const val defaultKeepUnusedLibraries = true
        const val defaultKeepUnusedPlugins = true
        const val defaultCheckConstraints = true
        const val defaultCheckBuildEnvironmentConstraints = false
        const val defaultCheckForGradleUpdate = true
        const val defaultReportFormats = "json,html"
        const val defaultOutputDirectory = "build/dependencyUpdates"
        const val defaultReportFileName = "report"
    }

    abstract class Extension {

        @get:Optional
        abstract val sortByKey: Property<Boolean>

        @get:Optional
        abstract val catalogFile: RegularFileProperty

        @get:Nested
        abstract val keep: KeepConfiguration

        @get:Nested
        abstract val pins: PinConfiguration

        @get:Nested
        abstract val check: CheckConfiguration

        @get:Nested
        abstract val versionCatalogs: NamedDomainObjectContainer<VersionCatalogConfig>

        @get:Nested
        abstract val gradle: GradleConfiguration

        @get:Nested
        abstract val report: ReportConfiguration

        fun keep(action: Action<KeepConfiguration>) = action.execute(keep)

        fun pins(action: Action<PinConfiguration>) = action.execute(pins)

        fun versionCatalogs(action: Action<NamedDomainObjectContainer<VersionCatalogConfig>>) = action.execute(versionCatalogs)

        fun check(action: Action<CheckConfiguration>) = action.execute(check)

        fun gradle(action: Action<GradleConfiguration>) = action.execute(gradle)

        fun report(action: Action<ReportConfiguration>) = action.execute(report)
    }

    abstract class CheckConfiguration {

        abstract val constraints: Property<Boolean>
        abstract val buildEnvironmentConstraints: Property<Boolean>
        abstract val forGradleUpdate: Property<Boolean>
    }

    abstract class GradleConfiguration {

        @get:Optional
        abstract val versionsApiBaseUrl: Property<String>

        @get:Optional
        abstract val releaseChannel: Property<String>
    }

    abstract class ReportConfiguration {

        @get:Optional
        abstract val formats: Property<String>

        @get:Optional
        abstract val outputDirectory: Property<String>

        @get:Optional
        abstract val fileName: Property<String>
    }

    private val ComponentSelectionWithCurrent.currentSemanticVersion: DependencyVersion get() = DependencyVersion(currentVersion)
    private val ComponentSelectionWithCurrent.candidateSemanticVersion: DependencyVersion get() = DependencyVersion(candidate.version)

    private fun ComponentSelectionWithCurrent.wouldDowngradeVersion(): Boolean = currentSemanticVersion > candidateSemanticVersion
    private fun ComponentSelectionWithCurrent.wouldDestabilizeAStableVersion(): Boolean = currentSemanticVersion.isStable && !candidateSemanticVersion.isStable
}