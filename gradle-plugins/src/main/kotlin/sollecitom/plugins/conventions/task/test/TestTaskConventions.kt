package sollecitom.plugins.conventions.task.test

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.KotlinClosure2
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.withType
import sollecitom.plugins.JvmConfiguration
import java.util.concurrent.atomic.AtomicLong

abstract class TestTaskConventions : Plugin<Project> {

    override fun apply(project: Project) {

        val testCount = project.rootProject.extra["testCount"] as AtomicLong
        val successfulTestCount = project.rootProject.extra["successfulTestCount"] as AtomicLong
        val failedTestCount = project.rootProject.extra["failedTestCount"] as AtomicLong
        val skippedTestCount = project.rootProject.extra["skippedTestCount"] as AtomicLong

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            if (isRunningOnRemoteBuildEnvironment()) {
                maxParallelForks = 1
                maxHeapSize = "1g"
            } else {
                maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
            }
            testLogging {
                showStandardStreams = false
                exceptionFormat = TestExceptionFormat.FULL
            }
            jvmArgs = JvmConfiguration.testArgs

            reports {
                junitXml.outputLocation.set(project.file("${project.rootProject.layout.buildDirectory.get()}/test-results/test/${project.name}"))
                html.outputLocation.set(project.file("${project.rootProject.layout.buildDirectory.get()}/test-results/reports/test/${project.name}"))
            }
            afterSuite(
                KotlinClosure2({ descriptor: TestDescriptor, result: TestResult ->
                    // Only execute on the outermost suite
                    if (descriptor.parent == null) {
                        println("\t>   Result:  ${result.resultType}")
                        println("\t>   Tests:   ${result.testCount}")
                        println("\t>   Passed:  ${result.successfulTestCount}")
                        println("\t>   Failed:  ${result.failedTestCount}")
                        println("\t>   Skipped: ${result.skippedTestCount}")
                        testCount.addAndGet(result.testCount)
                        successfulTestCount.addAndGet(result.successfulTestCount)
                        failedTestCount.addAndGet(result.failedTestCount)
                        skippedTestCount.addAndGet(result.skippedTestCount)
                    }
                })
            )
        }
    }

    private fun isRunningOnRemoteBuildEnvironment() = System.getenv("CI") != null
}