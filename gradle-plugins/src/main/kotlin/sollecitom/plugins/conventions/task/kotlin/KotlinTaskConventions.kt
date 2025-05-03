package sollecitom.plugins.conventions.task.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

abstract class KotlinTaskConventions : Plugin<Project> {

    override fun apply(project: Project) = with(project) {

        tasks.withType<KotlinCompile>().configureEach {

            compilerOptions {
                jvmTarget.set(targetJvmVersion)
                javaParameters.set(true)
                freeCompilerArgs.set(compilerArgs)
                progressiveMode.set(true)
            }
        }
    }

    companion object {
        private val optIns = emptyList<String>()
        private val optInCompilerArguments = optIns.map { "-opt-in=$it" }
        private val compilerArgs = optInCompilerArguments + listOf("-Xcontext-parameters", "-Xjsr305=strict", "-Xwarning-level=CONTEXT_RECEIVERS_DEPRECATED:disabled")
        private val targetJvmVersion = JvmTarget.JVM_23
    }
}