package sollecitom.tools.license_audit.app

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun main() {
    val policy = LicensePolicy(
        allowed = setOf("Apache-2.0", "MIT"),
        review = setOf("MPL-2.0", "LicenseRef-Internal-Review"),
        denied = setOf("GPL-3.0-only"),
        aliases = mapOf("Apache 2.0" to "Apache-2.0", "MIT License" to "MIT"),
        licenseNotes = emptyMap(),
        packageOverrides = emptyList(),
        internalGroups = listOf("sollecitom"),
        repoPolicyFile = "license-waivers.yml",
        allowRepoOverrideOfDenied = false,
    )

    val classifier = LicenseClassifier(policy)

    check(classifier.classify(listOf(LicenseStatement.fromExpression("GPL-3.0-only OR Apache-2.0"))).status == Status.ALLOW) {
        "Expected allowed branch of OR expression to pass."
    }

    check(classifier.classify(listOf(LicenseStatement.fromExpression("Apache-2.0 AND GPL-3.0-only"))).status == Status.DENY) {
        "Expected AND expression containing denied license to fail."
    }

    check(classifier.classify(listOf(LicenseStatement.single("Commercial License Agreement"))).status == Status.DENY) {
        "Expected proprietary license text to be denied."
    }

    check(classifier.classify(listOf(LicenseStatement.single("MIT License"))).displayLicenses == listOf("MIT")) {
        "Expected aliases to normalize before reporting."
    }

    check(classifier.classify(listOf(LicenseStatement.fromExpression("(Apache-2.0 OR MIT) AND GPL-3.0-only"))).status == Status.REVIEW) {
        "Expected mixed AND/OR expressions to stay review-required."
    }

    check(
        classifier.classify(
            listOf(
                LicenseStatement.single("LGPL-2.1-or-later"),
                LicenseStatement.single("Apache-2.0"),
            )
        ).status == Status.ALLOW
    ) {
        "Expected multiple license entries to pass when at least one branch is allowed."
    }

    checkExpiredWaiverIsNotServedFromCache()
    checkUnquotedWaiverDateIsAccepted()
    checkPackageOverrideChangesApplyToCachedComponents()
    checkMissingPomResolutionIsRetriedOnCachedRuns()

    println("License audit self-checks passed.")
}

private fun checkExpiredWaiverIsNotServedFromCache() = withSelfCheckWorkspace { workspace ->
    workspace.writePom(license = "Selfcheck Custom License")
    workspace.writeWaivers(
        """
        waivers:
          - package: ${SelfCheckWorkspace.COORDINATE}
            decision: allow
            owner: self-check
            reason: self-check
            expires: "2026-01-01"
        """.trimIndent()
    )

    check(workspace.audit(today = LocalDate.parse("2026-01-01")) == 0) { "Expected an active waiver to pass." }
    check(workspace.audit(today = LocalDate.parse("2026-01-02")) == 1) { "Expected an expired waiver to stop applying on a cached run." }
}

private fun checkUnquotedWaiverDateIsAccepted() = withSelfCheckWorkspace { workspace ->
    workspace.writePom(license = "Selfcheck Custom License")
    workspace.writeWaivers(
        """
        waivers:
          - package: ${SelfCheckWorkspace.COORDINATE}
            decision: allow
            owner: self-check
            reason: self-check
            expires: 2026-01-01
        """.trimIndent()
    )

    check(workspace.audit(today = LocalDate.parse("2026-01-01")) == 0) { "Expected an active waiver with an unquoted date to pass." }
}

private fun checkPackageOverrideChangesApplyToCachedComponents() = withSelfCheckWorkspace { workspace ->
    workspace.writePom(license = "MIT")
    check(workspace.audit() == 0) { "Expected an allowed POM license to pass." }

    workspace.writePolicy(
        """
        package_overrides:
          - package: ${SelfCheckWorkspace.COORDINATE}
            license: GPL-3.0-only
            reason: self-check
        """.trimIndent()
    )
    check(workspace.audit() == 1) { "Expected a newly added package override to apply to a cached component." }

    workspace.writePolicy()
    check(workspace.audit() == 0) { "Expected a removed package override to stop applying to a cached component." }
}

private fun checkMissingPomResolutionIsRetriedOnCachedRuns() = withSelfCheckWorkspace { workspace ->
    workspace.writePom(license = "MIT")
    check(workspace.audit() == 0) { "Expected an allowed POM license to pass." }

    workspace.markCachedComponentAsMissing()
    check(workspace.audit() == 0) { "Expected a cached missing POM resolution to be retried." }
}

private fun withSelfCheckWorkspace(block: (SelfCheckWorkspace) -> Unit) {
    val root = Files.createTempDirectory("license-audit-self-check")
    try {
        block(SelfCheckWorkspace(root))
    } finally {
        root.toFile().deleteRecursively()
    }
}

private class SelfCheckWorkspace(private val root: Path) {

    private val repoReports = root.resolve("$REPO/build/reports/license-audit")

    init {
        writePolicy()
        root.resolve("scripts").createDirectories().resolve("run-generate-license-snapshot.sh").writeText("exit 0\n")
        repoReports.createDirectories().resolve("dependency-snapshot.json").writeText(
            JSONObject(mapOf("repo" to REPO, "components" to JSONArray(listOf(JSONObject(mapOf("coordinate" to COORDINATE, "configurations" to JSONArray(listOf("runtimeClasspath")))))))).toString()
        )
    }

    fun writePolicy(extraYaml: String = "") {
        root.resolve("policy").createDirectories().resolve("license-policy.yml").writeText("allow:\n  - MIT\ndeny:\n  - GPL-3.0-only\n$extraYaml\n")
    }

    fun writeWaivers(yaml: String) {
        root.resolve("$REPO/license-waivers.yml").writeText(yaml)
    }

    fun writePom(license: String) {
        root.resolve(".workspace-run-state/license-audit/poms/invalid/selfcheck/component/1.0").createDirectories().resolve("component-1.0.pom")
            .writeText("<project><licenses><license><name>$license</name></license></licenses></project>")
    }

    fun markCachedComponentAsMissing() {
        val statePath = repoReports.resolve("state.json")
        val state = JSONObject(statePath.readText())
        state.getJSONArray("components").getJSONObject(0).put("resolutionSource", "missing").put("rawLicenses", JSONArray())
        state.put("findings", JSONArray(listOf(JSONObject(mapOf("status" to "UNKNOWN", "repo" to REPO, "component" to COORDINATE, "license" to "(missing)")))))
        statePath.writeText(state.toString())
    }

    fun audit(today: LocalDate = LocalDate.now()): Int = WorkspaceLicenseAudit(
        workspaceRoot = root,
        force = false,
        outputMode = OutputMode.COMPACT,
        out = {},
        today = today,
    ).run(listOf(REPO))

    companion object {
        const val REPO = "repo"
        const val COORDINATE = "pkg:maven/invalid.selfcheck/component@1.0"
    }
}
