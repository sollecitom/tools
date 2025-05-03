package sollecitom.plugins.conventions.task.container.test

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

// TODO rename to ContainerizedServiceTestConvention
abstract class ContainerBasedServiceTestConvention : Plugin<Project> {

    override fun apply(project: Project) = with(project) {

        val sourceSet = extensions.getByType<JavaPluginExtension>().sourceSets.create("containerBasedServiceTest")
        val extension = project.extensions.create<Extension>("containerBasedServiceTest")
        val containerBasedServiceTestTask = tasks.register<Test>("containerBasedServiceTest") {
            description = "Runs container-based service tests."
            group = "verification"
            useJUnitPlatform()

            testClassesDirs = sourceSet.output.classesDirs
            classpath = configurations[sourceSet.runtimeClasspathConfigurationName] + sourceSet.output
        }

        afterEvaluate {
            containerBasedServiceTestTask.configure {
                dependsOn(":${extension.starterModuleName.get()}:jibDockerBuild")
            }
        }
    }

    abstract class Extension {

        abstract val starterModuleName: Property<String>
    }
}

fun DependencyHandlerScope.containerBasedServiceTestImplementation(dependency: Any) = "containerBasedServiceTestImplementation"(dependency)