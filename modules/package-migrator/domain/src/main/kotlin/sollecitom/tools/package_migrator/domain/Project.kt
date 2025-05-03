package sollecitom.tools.package_migrator.domain

interface Project {

    val rootDirectory: Directory

    val excludedFolderNames: Set<String>

    companion object
}