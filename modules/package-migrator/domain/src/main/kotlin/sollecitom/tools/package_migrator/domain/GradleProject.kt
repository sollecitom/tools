package sollecitom.tools.package_migrator.domain

import java.nio.file.Path
import java.nio.file.Paths

internal class GradleProject(override val rootDirectory: Directory) : Project {

    init {
        require(rootDirectory.isAbsolute) { "Only absolute root folder paths are supported" }
    }

    override val excludedFolderNames: Set<String> get() = Companion.excludedFolderNames

    companion object {

        val excludedFolderNames = setOf("build", ".git", ".kotlin", "gradle")
    }
}

fun Project.Companion.jvm(projectRootDirectory: Directory): Project = GradleProject(rootDirectory = projectRootDirectory)

fun Project.Companion.jvm(projectRootDirectory: Path): Project = Project.jvm(projectRootDirectory.let(::Directory))

fun Project.Companion.jvm(projectRootDirectory: String): Project = Project.jvm(projectRootDirectory.let(Paths::get).let(::Directory))