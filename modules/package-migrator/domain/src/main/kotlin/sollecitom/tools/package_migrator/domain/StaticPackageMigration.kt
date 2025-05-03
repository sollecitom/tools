package sollecitom.tools.package_migrator.domain

import sollecitom.libs.swissknife.logger.core.loggable.Loggable
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class StaticPackageMigration(private val fromPackage: Package, private val toPackage: Package) : ProjectMigration {

    override fun applyTo(project: Project) = with(project) {

        notExcludedFiles.forEach { projectFile ->
            with(projectFile) {
                replaceTextIfPresent(
                    fromPackage.name to toPackage.name,
                    fromPackage.asPath.pathString to toPackage.asPath.pathString
                )

                takeIf { it.isWithinPackage(fromPackage) }?.movePackage(fromPackage, toPackage)
            }
        }
        directoriesWithinPackage(fromPackage).forEach {
            if (it.isEmpty) {
                it.delete()
            } else {
                it.path.movePackage(fromPackage, toPackage)
            }
        }
    }

    private fun Directory.delete() {

        deleteIfExists()
        logger.info { "Deleted directory $this" }
    }

    private val Project.notExcludedFiles get() = rootDirectory.files.filterNot { it.isWithinFolder(excludedFolderNames) }

    private fun Project.directoriesWithinPackage(containingPackage: Package): Sequence<Directory> = rootDirectory.directories.filter { it.isWithinPackage(containingPackage) }.sortedByDescending { it.pathString.length }.map(::Directory)

    private fun Path.movePackage(originalPackage: Package, targetPackage: Package) {

        val newPath = pathString.replaceFirst(originalPackage.asPath.pathString, targetPackage.asPath.pathString).let(Paths::get)
        newPath.parent.createDirectories()
        moveTo(newPath)
        logger.info { "Moved $this to $newPath" }
    }

    private fun Path.replaceTextIfPresent(vararg replacements: Pair<String, String>) {

        val originalContent = readText()
        val newContent = replacements.fold(originalContent) { content, (target, replacement) -> content.replace(target, replacement) }
        newContent.takeUnless { it == originalContent }?.let {
            writeText(it)
            logger.info { "Modified file $this" }
        }
    }

    private fun Path.isWithinFolder(folderNames: Set<String>): Boolean {

        val segments = pathString.split(File.separator)
        return segments.any { it in folderNames }
    }

    private fun Path.isWithinPackage(prospectiveContainingPackage: Package) = pathString.contains(prospectiveContainingPackage.asPath.pathString)

    companion object : Loggable()
}

fun ProjectMigration.Companion.changePackageName(from: String, to: String): ProjectMigration = StaticPackageMigration(fromPackage = from.let(::Package), toPackage = to.let(::Package))