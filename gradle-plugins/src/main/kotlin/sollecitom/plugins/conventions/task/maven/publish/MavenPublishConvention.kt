package sollecitom.plugins.conventions.task.maven.publish

import sollecitom.plugins.RepositoryConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get

abstract class MavenPublishConvention : Plugin<Project> {

    override fun apply(project: Project) = with(project) {

        pluginManager.apply(MavenPublishPlugin::class)

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                repositories {
                    RepositoryConfiguration.Publications.apply(this, project)
                }
                publications {
                    create("$name-maven", MavenPublication::class.java) {
                        groupId = rootProject.group.toString()
                        artifactId = project.name
                        version = rootProject.version.toString()
                        from(components["java"])
                        logger.quiet("Created publication ${groupId}:${artifactId}:${version}")
                    }
                }
            }
        }
    }
}