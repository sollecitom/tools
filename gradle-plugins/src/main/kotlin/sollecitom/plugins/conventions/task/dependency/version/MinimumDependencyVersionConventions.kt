package sollecitom.plugins.conventions.task.dependency.version

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.create

abstract class MinimumDependencyVersionConventions : Plugin<Project> {

    override fun apply(project: Project) = with(project) {

        val settings = project.extensions.create<Extension>("minimumDependencyVersionConventions")
        afterEvaluate {
            val vulnerableDependencies = settings.vulnerableDependencies
            project.configurations.all {
                resolutionStrategy.eachDependency {
                    // https://docs.gradle.org/current/userguide/resolution_rules.html
                    vulnerableDependencies.forEach { vulnerableDependency ->
                        if (vulnerableDependency.matches(requested)) {
                            useVersion(vulnerableDependency.minimumVersion.stringValue)
                        }
                    }
                }
            }
        }
    }

    private val Extension.vulnerableDependencies: List<MinimumDependencyVersion> get() = knownVulnerableDependencies.getOrElse(Extension.Companion.defaultVulnerableDependencies)

    abstract class Extension {

        @get:Optional
        abstract val knownVulnerableDependencies: ListProperty<MinimumDependencyVersion>

        internal companion object {
            val defaultVulnerableDependencies: List<MinimumDependencyVersion> = emptyList()
        }
    }
}