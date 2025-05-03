package sollecitom.plugins.conventions.task.dependency.update

import com.vdurmont.semver4j.Semver
import com.vdurmont.semver4j.SemverException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

sealed interface DependencyVersion : Comparable<DependencyVersion> {

    val isStable: Boolean
    val stringValue: String

    companion object
}

class SemverDependencyVersion(private val value: Semver) : DependencyVersion {

    override val isStable: Boolean get() = value.isStable
    override val stringValue: String get() = value.value

    override fun compareTo(other: DependencyVersion) = when (other) {
        is SemverDependencyVersion -> value.compareTo(other.value)
        is RawDependencyVersion -> stringValue.compareTo(other.stringValue)
        else -> error("Impossible to compare version '$this' with version '$other'")
    }

    companion object {
        fun fromRawVersion(rawVersion: String): SemverDependencyVersion {

            Semver.SemverType.values().forEach { type ->
                try {
                    return Semver(rawVersion, type).let(::SemverDependencyVersion)
                } catch (error: SemverException) {
                    // continue
                }
            }
            throw SemverException("Not a valid semantic version '$rawVersion'")
        }
    }
}

class DateDependencyVersion(private val releaseDate: LocalDate) : DependencyVersion {

    override val isStable = true
    override val stringValue: String
        get() = releaseDate.format(formatter)

    override fun compareTo(other: DependencyVersion) = releaseDate.compareTo((other as DateDependencyVersion).releaseDate)

    companion object {
        private val formatter = DateTimeFormatter.BASIC_ISO_DATE
        fun fromRawVersion(rawVersion: String) = LocalDate.parse(rawVersion, formatter).let(::DateDependencyVersion)
    }
}

class RawDependencyVersion(override val stringValue: String) : DependencyVersion {

    override val isStable = true

    override fun compareTo(other: DependencyVersion) = stringValue.compareTo((other as RawDependencyVersion).stringValue)

    companion object {
        fun fromRawVersion(rawVersion: String) = RawDependencyVersion(rawVersion)
    }
}

operator fun DependencyVersion.Companion.invoke(rawVersion: String): DependencyVersion = runCatching { SemverDependencyVersion.fromRawVersion(rawVersion) }.getOrElse { runCatching { DateDependencyVersion.fromRawVersion(rawVersion) }.getOrElse { RawDependencyVersion.fromRawVersion(rawVersion) } }