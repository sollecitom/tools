package sollecitom.tools.package_migrator.app

import sollecitom.tools.package_migrator.domain.Project
import sollecitom.tools.package_migrator.domain.jvm
import sollecitom.tools.package_migrator.domain.packageMigrations
import java.nio.file.Paths

private val targetProjectRootDirectory = Paths.get("").toAbsolutePath()

private val migrations = packageMigrations(
    "a.b.c" to "b.d"
)

fun main() {

    val project = Project.jvm(projectRootDirectory = targetProjectRootDirectory)
    migrations.forEach { migration -> migration.applyTo(project) }
}