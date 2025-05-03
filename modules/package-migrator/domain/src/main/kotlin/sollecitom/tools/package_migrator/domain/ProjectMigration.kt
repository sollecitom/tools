package sollecitom.tools.package_migrator.domain

interface ProjectMigration {

    fun applyTo(project: Project)

    companion object
}

fun packageMigrations(firstMigration: Pair<String, String>, vararg otherMigrations: Pair<String, String>): List<ProjectMigration> {

    return listOf(firstMigration, *otherMigrations).map { ProjectMigration.changePackageName(from = it.first, to = it.second) }
}