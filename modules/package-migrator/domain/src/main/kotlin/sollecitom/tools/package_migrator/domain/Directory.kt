package sollecitom.tools.package_migrator.domain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

@JvmInline
value class Directory(val path: Path) {

    init {
        require(path.isDirectory()) { "The path must be a directory" }
    }

    val isAbsolute: Boolean get() = path.isAbsolute

    val content: Sequence<Path> get() = Files.walk(path).asSequence()

    val files: Sequence<Path> get() = content.filter { it.isRegularFile() }

    val directories: Sequence<Path> get() = content.filter { it.isDirectory() }

    val isEmpty: Boolean get() = Files.list(path).use { it.findAny().isEmpty }

    fun deleteIfExists(): Boolean = path.deleteIfExists()
}