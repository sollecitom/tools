package sollecitom.tools.package_migrator.domain

import sollecitom.libs.swissknife.logger.core.loggable.Loggable
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
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

        val references = originalPackage.referencesRegex()
        notExcludedFiles.forEach { file ->
            file.replaceTextIfPresent(references) { reference -> if (reference.groups[PATH_REFERENCE] != null) targetPackage.asPath.pathString else targetPackage.name }
        }
    }

    private fun Project.movePackage(originalPackage: Package, targetPackage: Package) {

        notExcludedFiles.filter { it.isWithinPackage(originalPackage, root) }.forEach { file ->
            file.movePackage(originalPackage, targetPackage, root)
        }
        directoriesWithinPackage(originalPackage).forEach { directory ->
            if (directory.isEmpty) {
                directory.path.deleteWithEmptyParents(originalPackage, root)
            } else {
                directory.path.movePackage(originalPackage, targetPackage, root)
            }
        }
    }

    private fun newTemporaryPackage() = "package_migration_${UUID.randomUUID().toString().replace("-", "")}".let(::Package)

    private val Project.root: Path get() = rootDirectory.path

    private val Project.notExcludedFiles get() = rootDirectory.files.filterNot { it.isWithinFolder(excludedFolderNames, root) }

    private fun Project.directoriesWithinPackage(containingPackage: Package): Sequence<Directory> = rootDirectory.directories
        .filterNot { it.isWithinFolder(excludedFolderNames, root) }
        .filter { it.isWithinPackage(containingPackage, root) }
        .sortedByDescending { it.pathString.length }
        .filter { it.isDirectory() }
        .map(::Directory)

    private fun Path.movePackage(originalPackage: Package, targetPackage: Package, root: Path) {

        val segments = segmentsWithin(root)
        val start = segments.indexOfSegments(originalPackage.segments)
        val newSegments = segments.take(start) + targetPackage.segments + segments.drop(start + originalPackage.segments.size)
        val newPath = newSegments.fold(root, Path::resolve)
        newPath.parent.createDirectories()
        moveTo(newPath)
        logger.info { "Moved $this to $newPath" }
        deleteEmptyParents(upTo = sourceSetRoot(originalPackage, root))
    }

    private fun Path.deleteWithEmptyParents(containingPackage: Package, root: Path) {

        deleteExisting()
        logger.info { "Deleted directory $this" }
        deleteEmptyParents(upTo = sourceSetRoot(containingPackage, root))
    }

    private fun Path.deleteEmptyParents(upTo: Path) = generateSequence(parent, Path::getParent)
        .takeWhile { it != upTo && it.startsWith(upTo) }
        .takeWhile { it.listDirectoryEntries().isEmpty() }
        .forEach {
            it.deleteExisting()
            logger.info { "Deleted directory $it" }
        }

    private fun Path.sourceSetRoot(containingPackage: Package, root: Path): Path = segmentsWithin(root)
        .let { segments -> segments.take(segments.indexOfSegments(containingPackage.segments)) }
        .fold(root, Path::resolve)

    private fun Path.replaceTextIfPresent(target: Regex, replacement: (MatchResult) -> CharSequence) {

        val originalContent = readText()
        val newContent = originalContent.replace(target, replacement)
        newContent.takeUnless { it == originalContent }?.let {
            writeText(it)
            logger.info { "Modified file $this" }
        }
    }

    private fun Path.isWithinFolder(folderNames: Set<String>, root: Path) = segmentsWithin(root).any { it in folderNames }

    private fun Path.isWithinPackage(prospectiveContainingPackage: Package, root: Path) = segmentsWithin(root).indexOfSegments(prospectiveContainingPackage.segments) >= 0

    private fun Path.segmentsWithin(root: Path): List<String> = root.relativize(this).map(Path::pathString)

    private fun List<String>.indexOfSegments(target: List<String>): Int = windowed(target.size).indexOf(target)

    private fun Package.referencesRegex() = Regex("(?<$PATH_REFERENCE>${pathReferencePattern()})|${name.asWholeSegmentsPattern()}")

    private fun Package.pathReferencePattern(): String {

        val separator = Regex.escape(File.separator)
        val pattern = asPath.pathString.asWholeSegmentsPattern()
        return if (segments.size > 1) pattern else "$pattern(?=$separator)|(?<=$separator)$pattern"
    }

    private fun String.asWholeSegmentsPattern() = """(?<![\w$])${Regex.escape(this)}(?![\w$])"""

    companion object : Loggable() {

        private const val PATH_REFERENCE = "path"
    }
}

fun ProjectMigration.Companion.changePackageName(from: String, to: String): ProjectMigration = StaticPackageMigration(fromPackage = from.let(::Package), toPackage = to.let(::Package))
