package sollecitom.plugins.conventions.task.dependency.version

import sollecitom.plugins.conventions.task.dependency.update.DependencyVersion
import sollecitom.plugins.conventions.task.dependency.update.invoke
import org.gradle.api.artifacts.ModuleVersionSelector

data class MinimumDependencyVersion(val group: String, val name: String, val minimumVersion: DependencyVersion) {

    constructor(group: String, name: String, minimumVersion: String) : this(group = group, name = name, minimumVersion = DependencyVersion.Companion(minimumVersion))

    fun matches(requested: ModuleVersionSelector): Boolean = requested.group == group && requested.name == name
}