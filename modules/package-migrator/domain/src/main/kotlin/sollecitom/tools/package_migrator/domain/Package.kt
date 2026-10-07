package sollecitom.tools.package_migrator.domain

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

@JvmInline
value class Package(val name: String) {

    init {
        require(name.isValidPackageName()) { "Invalid package name '$name'" }
    }

    val asPath: Path get() = name.replace(SEPARATOR, File.separator).let(Paths::get)

    val segments: List<String> get() = name.split(SEPARATOR)

    private companion object {

        private const val SEPARATOR = "."

        private val regex = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*\$").asPredicate()

        private fun String.isValidPackageName(): Boolean = regex.test(this)
    }
}