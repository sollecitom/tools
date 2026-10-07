package sollecitom.tools.license_audit.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

@TestInstance(PER_CLASS)
class WorkspaceLicenseAuditTests {

    @Test
    fun `an expired waiver stops applying on a cached run`(@TempDir root: Path) {

        val workspace = AuditedWorkspace(root)
        workspace.writePom(license = "Custom License")
        workspace.writeWaiver(expires = "\"2026-01-01\"")

        val exitCodeWhileActive = workspace.audit(today = LocalDate.parse("2026-01-01"))
        val exitCodeOnceExpired = workspace.audit(today = LocalDate.parse("2026-01-02"))

        assertThat(exitCodeWhileActive).isEqualTo(0)
        assertThat(exitCodeOnceExpired).isEqualTo(1)
    }

    @Test
    fun `a waiver with an unquoted expiry date is accepted`(@TempDir root: Path) {

        val workspace = AuditedWorkspace(root)
        workspace.writePom(license = "Custom License")
        workspace.writeWaiver(expires = "2026-01-01")

        val exitCode = workspace.audit(today = LocalDate.parse("2026-01-01"))

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `package override changes apply to cached components`(@TempDir root: Path) {

        val workspace = AuditedWorkspace(root)
        workspace.writePom(license = "MIT")
        val exitCodeWithoutOverride = workspace.audit()

        workspace.writePolicy(packageOverridesYaml = "package_overrides:\n  - package: ${AuditedWorkspace.COORDINATE}\n    license: GPL-3.0-only\n    reason: test")
        val exitCodeWithOverride = workspace.audit()

        workspace.writePolicy()
        val exitCodeOnceOverrideRemoved = workspace.audit()

        assertThat(exitCodeWithoutOverride).isEqualTo(0)
        assertThat(exitCodeWithOverride).isEqualTo(1)
        assertThat(exitCodeOnceOverrideRemoved).isEqualTo(0)
    }

    @Test
    fun `a cached missing POM resolution is retried`(@TempDir root: Path) {

        val workspace = AuditedWorkspace(root)
        workspace.writePom(license = "MIT")
        workspace.audit()
        workspace.markCachedComponentAsMissing()

        val exitCode = workspace.audit()

        assertThat(exitCode).isEqualTo(0)
    }

    private class AuditedWorkspace(private val root: Path) {

        private val repoReports = root.resolve("$REPO/build/reports/license-audit")

        init {
            writePolicy()
            root.resolve("scripts").createDirectories().resolve("run-generate-license-snapshot.sh").writeText("exit 0\n")
            repoReports.createDirectories().resolve("dependency-snapshot.json").writeText(
                JSONObject(mapOf("repo" to REPO, "components" to JSONArray(listOf(JSONObject(mapOf("coordinate" to COORDINATE, "configurations" to JSONArray(listOf("runtimeClasspath")))))))).toString()
            )
        }

        fun writePolicy(packageOverridesYaml: String = "") {
            root.resolve("policy").createDirectories().resolve("license-policy.yml").writeText("allow:\n  - MIT\ndeny:\n  - GPL-3.0-only\n$packageOverridesYaml\n")
        }

        fun writeWaiver(expires: String) {
            root.resolve("$REPO/license-waivers.yml").writeText("waivers:\n  - package: $COORDINATE\n    decision: allow\n    owner: test\n    reason: test\n    expires: $expires\n")
        }

        fun writePom(license: String) {
            root.resolve(".workspace-run-state/license-audit/poms/invalid/test/component/1.0").createDirectories().resolve("component-1.0.pom")
                .writeText("<project><licenses><license><name>$license</name></license></licenses></project>")
        }

        fun markCachedComponentAsMissing() {
            val statePath = repoReports.resolve("state.json")
            val state = statePath.readText().let(::JSONObject)
            state.getJSONArray("components").getJSONObject(0).put("resolutionSource", "missing").put("rawLicenses", JSONArray())
            state.put("findings", JSONArray(listOf(JSONObject(mapOf("status" to "UNKNOWN", "repo" to REPO, "component" to COORDINATE, "license" to "(missing)")))))
            statePath.writeText(state.toString())
        }

        fun audit(today: LocalDate = LocalDate.parse("2026-01-01")): Int = WorkspaceLicenseAudit(workspaceRoot = root, force = false, outputMode = OutputMode.COMPACT, out = {}, today = today).run(listOf(REPO))

        companion object {
            const val REPO = "repo"
            const val COORDINATE = "pkg:maven/invalid.test/component@1.0"
        }
    }
}
