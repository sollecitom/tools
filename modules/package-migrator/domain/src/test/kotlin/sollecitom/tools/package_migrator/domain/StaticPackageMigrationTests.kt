package sollecitom.tools.package_migrator.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

@TestInstance(PER_CLASS)
class StaticPackageMigrationTests {

    @Test
    fun `only whole package segments are migrated, in contents and paths`(@TempDir temporaryDirectory: Path) {

        val root = temporaryDirectory.resolve("a/b/c-service").createDirectories()
        root.file("src/main/kotlin/a/b/c/Foo.kt", "package a.b.c\nimport a.b.common.Bar\nimport xa.b.c.Baz\nval path = \"src/main/kotlin/a/b/c\"\n")
        root.file("src/main/kotlin/a/b/c/d/Qux.kt", "package a.b.c.d\n")
        root.file("src/main/kotlin/a/b/common/Bar.kt", "package a.b.common\n")
        val project = Project.jvm(root)

        ProjectMigration.changePackageName(from = "a.b.c", to = "b.d").applyTo(project)

        assertThat(root.resolve("src/main/kotlin/b/d/Foo.kt").readText()).isEqualTo("package b.d\nimport a.b.common.Bar\nimport xa.b.c.Baz\nval path = \"src/main/kotlin/b/d\"\n")
        assertThat(root.resolve("src/main/kotlin/b/d/d/Qux.kt").readText()).isEqualTo("package b.d.d\n")
        assertThat(root.resolve("src/main/kotlin/a/b/common/Bar.kt").readText()).isEqualTo("package a.b.common\n")
        assertThat(root.resolve("src/main/kotlin/a/b/c").exists()).isFalse()
    }

    @Test
    fun `a package can be migrated into one of its own subpackages`(@TempDir root: Path) {

        root.file("src/main/kotlin/a/b/Foo.kt", "package a.b\nimport a.b.c.Qux\n")
        root.file("src/main/kotlin/a/b/c/Qux.kt", "package a.b.c\n")
        val project = Project.jvm(root)

        ProjectMigration.changePackageName(from = "a.b", to = "a.b.v2").applyTo(project)

        assertThat(root.resolve("src/main/kotlin/a/b/v2/Foo.kt").readText()).isEqualTo("package a.b.v2\nimport a.b.v2.c.Qux\n")
        assertThat(root.resolve("src/main/kotlin/a/b/v2/c/Qux.kt").readText()).isEqualTo("package a.b.v2.c\n")
        assertThat(root.resolve("src/main/kotlin/a/b").listDirectoryEntries().map { it.name }).containsExactly("v2")
        assertThat(root.resolve("src/main/kotlin").listDirectoryEntries().map { it.name }).containsExactly("a")
    }

    @Test
    fun `excluded directories are never moved or rewritten`(@TempDir root: Path) {

        root.file("src/main/kotlin/a/b/c/Foo.kt", "package a.b.c\n")
        root.file("build/generated/a/b/c/Gen.kt", "package a.b.c\n")
        root.file(".idea/a/b/c/Workspace.kt", "package a.b.c\n")
        val project = Project.jvm(root)

        ProjectMigration.changePackageName(from = "a.b.c", to = "b.d").applyTo(project)

        assertThat(root.resolve("src/main/kotlin/b/d/Foo.kt").exists()).isTrue()
        assertThat(root.resolve("build/generated/a/b/c/Gen.kt").readText()).isEqualTo("package a.b.c\n")
        assertThat(root.resolve(".idea/a/b/c/Workspace.kt").readText()).isEqualTo("package a.b.c\n")
    }

    private fun Path.file(relativePath: String, content: String) = resolve(relativePath).also { it.parent.createDirectories() }.writeText(content)
}
