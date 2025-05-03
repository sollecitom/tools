package sollecitom.plugins.conventions.task.test

import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.kotlin.dsl.extra
import java.util.concurrent.atomic.AtomicLong

abstract class AggregateTestMetricsConventions : Plugin<Project> {

    override fun apply(project: Project) {

        val testCount = AtomicLong(0L)
        project.extra["testCount"] = testCount
        val successfulTestCount = AtomicLong(0L)
        project.extra["successfulTestCount"] = successfulTestCount
        val failedTestCount = AtomicLong(0L)
        project.extra["failedTestCount"] = failedTestCount
        val skippedTestCount = AtomicLong(0L)
        project.extra["skippedTestCount"] = skippedTestCount

        project.gradle.addBuildListener(object : BuildListener {

            override fun settingsEvaluated(settings: Settings) {

            }

            override fun projectsLoaded(gradle: Gradle) {

            }

            override fun projectsEvaluated(gradle: Gradle) {

            }

            @Deprecated(message = "Apparently not supported anymore")
            override fun buildFinished(result: BuildResult) {

                println()
                println("> ${project.name}: Aggregated Test Metrics:")
                println("\t>   Total Tests Run:     ${testCount.get()}")
                println("\t>   Total Tests Passed:  ${successfulTestCount.get()}")
                println("\t>   Total Tests Failed:  ${failedTestCount.get()}")
                println("\t>   Total Tests Skipped: ${skippedTestCount.get()}")
            }
        })
    }
}