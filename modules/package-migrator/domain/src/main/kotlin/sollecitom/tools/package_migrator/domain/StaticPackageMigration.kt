package sollecitom.tools.package_migrator.domain

import sollecitom.libs.swissknife.logger.core.loggable.Loggable
import java.io.File
import java.nio.file.Path
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
                    fromPackage.name.asWholeSegmentsRegex() to toPackage.name,
                    fromPackage.asPath.pathString.asWholeSegmentsRegex() to toPackage.asPath.pathString
                )

                takeIf { it.isWithinPackage(fromPackage, rootDirectory.path) }?.movePackage(fromPackage, toPackage, rootDirectory.path)
            }
        }
        directoriesWithinPackage(fromPackage).forEach {
            if (it.isEmpty) {
                it.delete()
            } else {
                it.path.movePackage(fromPackage, toPackage, rootDirectory.path)
            }
        }
    }

    private fun Directory.delete() {

        deleteIfExists()
        logger.info { "Deleted directory $this" }
    }

    private val Project.notExcludedFiles get() = rootDirectory.files.filterNot { it.isWithinFolder(excludedFolderNames, rootDirectory.path) }

    private fun Project.directoriesWithinPackage(containingPackage: Package): Sequence<Directory> = rootDirectory.directories.filterNot { it.isWithinFolder(excludedFolderNames, rootDirectory.path) }.filter { it.isWithinPackage(containingPackage, rootDirectory.path) }.sortedByDescending { it.pathString.length }.map(::Directory)

    private fun Path.movePackage(originalPackage: Package, targetPackage: Package, root: Path) {

        val segments = root.relativize(this).segments
        val packageSegments = originalPackage.asPath.segments
        val start = segments.indexOfSegments(packageSegments)
        val newSegments = segments.take(start) + targetPackage.asPath.segments + segments.drop(start + packageSegments.size)
        val newPath = newSegments.fold(root, Path::resolve)
        newPath.parent.createDirectories()
        moveTo(newPath)
        logger.info { "Moved $this to $newPath" }
    }

    private fun Path.replaceTextIfPresent(vararg replacements: Pair<Regex, String>) {

        val originalContent = readText()
        val newContent = replacements.fold(originalContent) { content, (target, replacement) -> content.replace(target, Regex.escapeReplacement(replacement)) }
        newContent.takeUnless { it == originalContent }?.let {
            writeText(it)
            logger.info { "Modified file $this" }
        }
    }

    private fun Path.isWithinFolder(folderNames: Set<String>, root: Path): Boolean {

        val segments = root.relativize(this).pathString.split(File.separator)
        return segments.any { it in folderNames }
    }

    private fun Path.isWithinPackage(prospectiveContainingPackage: Package, root: Path) = root.relativize(this).segments.indexOfSegments(prospectiveContainingPackage.asPath.segments) >= 0

    private val Path.segments: List<String> get() = map(Path::pathString)

    private fun List<String>.indexOfSegments(target: List<String>): Int = windowed(target.size).indexOf(target)

    private fun String.asWholeSegmentsRegex() = Regex("""(?<![\w$])${Regex.escape(this)}(?![\w$])""")

    companion object : Loggable()
}

fun ProjectMigration.Companion.changePackageName(from: String, to: String): ProjectMigration = StaticPackageMigration(fromPackage = from.let(::Package), toPackage = to.let(::Package))