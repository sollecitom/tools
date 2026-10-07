package sollecitom.tools.package_migrator.domain

import sollecitom.libs.swissknife.logger.core.loggable.Loggable
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class StaticPackageMigration(private val fromPackage: Package, private val toPackage: Package) : ProjectMigration {

    override fun applyTo(project: Project) {

        val temporaryPackage = newTemporaryPackage()
        project.rewriteReferences(fromPackage, toPackage)
        project.movePackage(fromPackage, temporaryPackage)
        project.movePackage(temporaryPackage, toPackage)
    }

    private fun Project.rewriteReferences(originalPackage: Package, targetPackage: Package) {

        notExcludedFiles.forEach { file ->
            file.replaceTextIfPresent(
                originalPackage.name.asWholeSegmentsRegex() to targetPackage.name,
                originalPackage.asPath.pathString.asWholeSegmentsRegex() to targetPackage.asPath.pathString
            )
        }
    }

    private fun Project.movePackage(originalPackage: Package, targetPackage: Package) {

        notExcludedFiles.filter { it.isWithinPackage(originalPackage, root) }.forEach { file ->
            file.movePackage(originalPackage, targetPackage, root)
        }
        directoriesWithinPackage(originalPackage).forEach { directory ->
            if (directory.isEmpty) {
                directory.delete()
            } else {
                directory.path.movePackage(originalPackage, targetPackage, root)
            }
        }
    }

    private fun newTemporaryPackage() = "package_migration_${UUID.randomUUID().toString().replace("-", "")}".let(::Package)

    private fun Directory.delete() {

        deleteIfExists()
        logger.info { "Deleted directory $this" }
    }

    private val Project.root: Path get() = rootDirectory.path

    private val Project.notExcludedFiles get() = rootDirectory.files.filterNot { it.isWithinFolder(excludedFolderNames, root) }

    private fun Project.directoriesWithinPackage(containingPackage: Package): Sequence<Directory> = rootDirectory.directories
        .filterNot { it.isWithinFolder(excludedFolderNames, root) }
        .filter { it.isWithinPackage(containingPackage, root) }
        .sortedByDescending { it.pathString.length }
        .map(::Directory)

    private fun Path.movePackage(originalPackage: Package, targetPackage: Package, root: Path) {

        val segments = segmentsWithin(root)
        val start = segments.indexOfSegments(originalPackage.segments)
        val newSegments = segments.take(start) + targetPackage.segments + segments.drop(start + originalPackage.segments.size)
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

    private fun Path.isWithinFolder(folderNames: Set<String>, root: Path) = segmentsWithin(root).any { it in folderNames }

    private fun Path.isWithinPackage(prospectiveContainingPackage: Package, root: Path) = segmentsWithin(root).indexOfSegments(prospectiveContainingPackage.segments) >= 0

    private fun Path.segmentsWithin(root: Path): List<String> = root.relativize(this).map(Path::pathString)

    private fun List<String>.indexOfSegments(target: List<String>): Int = windowed(target.size).indexOf(target)

    private fun String.asWholeSegmentsRegex() = Regex("""(?<![\w$])${Regex.escape(this)}(?![\w$])""")

    companion object : Loggable()
}

fun ProjectMigration.Companion.changePackageName(from: String, to: String): ProjectMigration = StaticPackageMigration(fromPackage = from.let(::Package), toPackage = to.let(::Package))
