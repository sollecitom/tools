package sollecitom.tools.package_migrator.app

import sollecitom.tools.package_migrator.domain.Project
import sollecitom.tools.package_migrator.domain.jvm
import sollecitom.tools.package_migrator.domain.packageMigrations
import java.nio.file.Path
import java.nio.file.Paths

private val migrations = packageMigrations(
    "a.b.c" to "b.d"
)

fun main(args: Array<String>) {

    val project = Project.jvm(projectRootDirectory = args.projectRootDirectory())
    migrations.forEach { migration -> migration.applyTo(project) }
}

private fun Array<String>.projectRootDirectory(): Path {

    val index = indexOf("--project")
    if (index < 0) return Paths.get("").toAbsolutePath()
    val value = getOrNull(index + 1) ?: error("--project requires a directory")
    return Paths.get(value).toAbsolutePath().normalize()
}
