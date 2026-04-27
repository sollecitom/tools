package sollecitom.tools.license_audit.app

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.system.exitProcess

private const val CACHE_SCHEMA_VERSION = 7
private const val MAVEN_LICENSE_CACHE_SCHEMA_VERSION = 2

private val defaultRepos = listOf(
    "gradle-plugins",
    "acme-schema-catalogue",
    "swissknife",
    "pillar",
    "tools",
    "examples",
    "facts",
    "backend-skeleton",
    "modulith-example",
    "element-service-example",
    "lattice",
)

fun main(args: Array<String>) {

    if (args.isEmpty() || args.first() != "workspace") {
        System.err.println("Usage: LicenseAudit workspace [--force] [repo ...]")
        exitProcess(2)
    }

    val force = args.drop(1).any { it == "--force" } || System.getenv("FORCE_LICENSE_AUDIT") == "1"
    val workspaceRoot = findWorkspaceRoot()
    val repos = args.drop(1).filterNot { it == "--force" }.ifEmpty { defaultRepos }
    exitProcess(WorkspaceLicenseAudit(workspaceRoot = workspaceRoot, force = force).run(repos))
}

private fun findWorkspaceRoot() = generateSequence(Path("").toAbsolutePath().normalize()) { it.parent }
    .firstOrNull { candidate -> candidate.name == "workspace" && candidate.resolve("tools").isDirectory() }
    ?: error("Run license audit from the workspace root or from a directory inside it.")

internal class WorkspaceLicenseAudit(
    private val workspaceRoot: java.nio.file.Path,
    private val force: Boolean,
    private val out: (String) -> Unit = ::println,
) {

    private val yaml = Yaml()
    private val policy = LicensePolicy.from(loadMap(workspaceRoot.resolve("policy/license-policy.yml")))
    private val classifier = LicenseClassifier(policy)
    private val licenseResolver = MavenLicenseResolver(workspaceRoot = workspaceRoot)
    private val sharedFingerprintInputs = listOf(
        workspaceRoot.resolve("policy/license-policy.yml"),
        workspaceRoot.resolve("scripts/cyclonedx-init.gradle"),
        workspaceRoot.resolve("scripts/run-generate-sbom.sh"),
        workspaceRoot.resolve("tools/modules/license-audit/app/src/main/kotlin/sollecitom/tools/license_audit/app/LicenseAudit.kt"),
    )

    fun run(repos: List<String>): Int {
        val warnings = mutableListOf<String>()
        val results = repos.map { repo -> processRepo(repo, warnings) }
        val findings = results.flatMap { result -> result.findings }
        val allowedLicenseCounts = results
            .flatMap { result -> result.allowedLicenseCounts.entries }
            .groupingBy { entry -> entry.key }
            .fold(0) { acc, entry -> acc + entry.value }
        licenseResolver.flush()

        printWarnings(warnings)
        printAllowedLicenseCounts(allowedLicenseCounts)
        printFindings(findings)
        return if (findings.any { it.status == Status.DENY }) 1 else 0
    }

    private fun processRepo(repo: String, warnings: MutableList<String>): RepoAuditResult {
        val repoPath = workspaceRoot.resolve(repo)
        require(repoPath.isDirectory()) { "Missing repo directory: $repoPath" }

        val cachePath = repoPath.resolve("build/reports/license-audit/state.json")
        val inputFingerprint = computeInputFingerprint(repoPath)
        val cachedState = loadCacheState(cachePath)

        if (!force && cachedState?.schemaVersion == CACHE_SCHEMA_VERSION && cachedState.inputFingerprint == inputFingerprint) {
            out("Skipping SBOM for $repo; dependency inputs unchanged.")
        return RepoAuditResult(findings = cachedState.findings, allowedLicenseCounts = cachedState.allowedLicenseCounts)
        }

        generateSbom(repo, repoPath)
        val repoSbom = assembleRepoSbom(repo, repoPath)
        val waivers = loadWaivers(repoPath, warnings)
        val evaluation = evaluateRepo(repo = repo, repoSbom = repoSbom, waivers = waivers)
        val sbomFingerprint = computeSbomFingerprint(repoSbom.document)
        val outcomeFingerprint = computeOutcomeFingerprint(evaluation.findings, evaluation.allowedLicenseCounts)

        if (!force && cachedState?.schemaVersion == CACHE_SCHEMA_VERSION && cachedState.outcomeFingerprint == outcomeFingerprint) {
            out("License outcome unchanged for $repo.")
        }

        writeCacheState(
            cachePath = cachePath,
            state = CachedRepoState(
                schemaVersion = CACHE_SCHEMA_VERSION,
                inputFingerprint = inputFingerprint,
                sbomFingerprint = sbomFingerprint,
                outcomeFingerprint = outcomeFingerprint,
                findings = evaluation.findings,
                allowedLicenseCounts = evaluation.allowedLicenseCounts,
            )
        )

        return RepoAuditResult(findings = evaluation.findings, allowedLicenseCounts = evaluation.allowedLicenseCounts)
    }

    private fun computeInputFingerprint(repoPath: java.nio.file.Path): String {
        val files = linkedSetOf<java.nio.file.Path>()
        files += sharedFingerprintInputs.filter { path -> path.exists() }
        files += repoInputFiles(repoPath)
        return sha256OfFiles(basePath = workspaceRoot, files = files)
    }

    private fun repoInputFiles(repoPath: java.nio.file.Path): List<java.nio.file.Path> {
        val includedNames = setOf(
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "gradle.properties",
            "libs.versions.toml",
            "container-versions.properties",
            policy.repoPolicyFile,
        )

        return repoPath.walk()
            .filter { path ->
                path.exists() &&
                    Files.isRegularFile(path) &&
                    path.name in includedNames &&
                    !path.toString().contains("/build/")
            }
            .sortedBy { path -> path.relativeTo(repoPath).toString() }
            .toList()
    }

    private fun generateSbom(repo: String, repoPath: java.nio.file.Path) {
        out("Generating SBOM for $repo")
        runCommand(repoPath, listOf("just", "generate-sbom"))
    }

    private fun assembleRepoSbom(repo: String, repoPath: java.nio.file.Path): RepoSbom {
        val directFiles = repoPath.walk()
            .filter { path -> path.name == "direct-cyclonedx.json" }
            .sortedBy { path -> path.relativeTo(repoPath).toString() }
            .toList()

        val outputPath = repoPath.resolve("build/reports/sbom/cyclonedx.json")
        outputPath.parent.createDirectories()

        if (directFiles.isEmpty()) {
            require(outputPath.exists()) { "Expected direct SBOMs or aggregate SBOM for $repo under $repoPath" }
            return RepoSbom(path = outputPath, document = JSONObject(outputPath.readText()))
        }

        val merged = linkedMapOf<String, JSONObject>()
        directFiles.forEach { directFile ->
            val parsed = JSONObject(directFile.readText())
            val components = parsed.optJSONArray("components") ?: JSONArray()
            repeat(components.length()) { index ->
                val component = components.optJSONObject(index) ?: return@repeat
                merged.putIfAbsent(componentKey(component), JSONObject(component.toString()))
            }
        }

        val document = JSONObject(
            mapOf(
                "bomFormat" to "CycloneDX",
                "specVersion" to "1.6",
                "serialNumber" to "urn:uuid:${UUID.randomUUID()}",
                "version" to 1,
                "metadata" to JSONObject(
                    mapOf(
                        "timestamp" to Instant.now().toString(),
                        "tools" to JSONArray(
                            listOf(
                                JSONObject(
                                    mapOf(
                                        "vendor" to "workspace",
                                        "name" to "tools-license-audit-app",
                                        "version" to CACHE_SCHEMA_VERSION.toString(),
                                    )
                                )
                            )
                        ),
                        "component" to JSONObject(
                            mapOf(
                                "type" to "application",
                                "name" to repo,
                            )
                        ),
                    )
                ),
                "components" to JSONArray(
                    merged.values
                        .sortedBy(::componentKey)
                        .map { component -> JSONObject(component.toString()) }
                ),
            )
        )

        outputPath.outputStream().use { output -> output.writer().use { writer -> writer.write(document.toString(2) + "\n") } }
        return RepoSbom(path = outputPath, document = document)
    }

    private fun componentKey(component: JSONObject) = component.optString("bom-ref").ifBlank {
        component.optString("purl").ifBlank {
            listOf(
                component.optString("group"),
                component.optString("name"),
                component.optString("version"),
            ).joinToString(":")
        }
    }

    private fun computeSbomFingerprint(document: JSONObject): String {
        val components = document.optJSONArray("components") ?: JSONArray()
        val normalized = buildList {
            repeat(components.length()) { index ->
                val component = components.optJSONObject(index) ?: return@repeat
                val licenses = rawLicenseStatements(component)
                    .flatMap { statement -> statement.licenses }
                    .sorted()
                    .joinToString("|")
                add("${componentCoordinate(component).orEmpty()}::$licenses")
            }
        }.sorted().joinToString("\n")
        return sha256(normalized)
    }

    private fun evaluateRepo(repo: String, repoSbom: RepoSbom, waivers: List<Waiver>): RepoEvaluation {
        val components = repoSbom.document.optJSONArray("components") ?: JSONArray()
        val findings = mutableListOf<Finding>()
        val allowedCounts = mutableMapOf<String, Int>()
        repeat(components.length()) { index ->
            val component = components.optJSONObject(index) ?: return@repeat
            val result = evaluateComponent(repo = repo, component = component, waivers = waivers) ?: return@repeat
            result.finding?.let(findings::add)
            result.allowedLicenses.forEach { license ->
                allowedCounts[license] = (allowedCounts[license] ?: 0) + 1
            }
        }
        return RepoEvaluation(findings = findings, allowedLicenseCounts = allowedCounts.toSortedMap())
    }

    private fun evaluateComponent(repo: String, component: JSONObject, waivers: List<Waiver>): ComponentAuditResult? {
        val coordinate = componentCoordinate(component) ?: return null
        if (policy.isInternalComponent(component = component, coordinate = coordinate)) return null

        val packageOverride = policy.packageOverrideFor(coordinate)
        val resolution = if (packageOverride != null) {
            MavenLicenseResolution(
                licenses = listOf(packageOverride.license),
                source = "policy-override",
                detail = "workspace policy override: ${packageOverride.reason}",
            )
        } else {
            licenseResolver.resolve(component)
        }
        val statements = rawLicenseStatements(component).ifEmpty {
            resolution.licenses.map(LicenseStatement::single)
        }
        val evaluation = classifier.classify(statements)
        val waived = applyWaiver(
            status = evaluation.status,
            coordinate = coordinate,
            displayLicenses = evaluation.displayLicenses,
            waivers = waivers,
        )
        val status = waived.status

        return if (status == Status.ALLOW) {
            ComponentAuditResult(finding = null, allowedLicenses = evaluation.allowedLicenses)
        } else {
            val detail = findingDetail(
                status = status,
                evaluation = evaluation,
                resolution = resolution,
                waiver = waived.waiver,
            )
            ComponentAuditResult(
                finding = Finding(
                    status = status,
                    repo = repo,
                    component = coordinate,
                    license = evaluation.displayLicenses.ifEmpty { listOf("(missing)") }.joinToString(" | "),
                    detail = detail,
                ),
                allowedLicenses = emptyList(),
            )
        }
    }

    private fun findingDetail(
        status: Status,
        evaluation: LicenseEvaluation,
        resolution: MavenLicenseResolution,
        waiver: Waiver?,
    ): String? {
        val baseReason = when (status) {
            Status.DENY -> when {
                evaluation.reason.isNotBlank() -> evaluation.reason
                else -> "license denied by workspace policy"
            }
            Status.REVIEW -> when {
                evaluation.reason.isNotBlank() -> evaluation.reason
                else -> "license requires manual review under workspace policy"
            }
            Status.UNKNOWN -> resolution.detail ?: when (resolution.source) {
                "missing" -> "artifact POM unavailable locally or upstream"
                "parent-missing" -> "artifact and parent POM chain did not expose a license"
                "unlicensed" -> "published POM has no license metadata"
                "pom" -> "published POM license name did not match policy aliases"
                "parent" -> "parent POM license name did not match policy aliases"
                else -> if (evaluation.displayLicenses.isEmpty()) "no license metadata found" else "license not recognized by policy"
            }
            Status.ALLOW -> null
        }

        return listOfNotNull(
            baseReason?.takeIf { it.isNotBlank() },
            evaluation.policyNotes.takeIf { it.isNotEmpty() }?.joinToString(" | "),
            waiver?.let { "repo waiver -> ${it.decision.name.lowercase()} by ${it.owner} until ${it.expires}: ${it.reason}" },
        ).joinToString("; ").takeIf { it.isNotBlank() }
    }

    private fun applyWaiver(status: Status, coordinate: String, displayLicenses: List<String>, waivers: List<Waiver>): WaiverDecision {
        val waiver = waivers.firstOrNull { waiver ->
            waiver.`package` == coordinate && (waiver.license == null || waiver.license in displayLicenses)
        } ?: return WaiverDecision(status = status, waiver = null)

        if (status == Status.DENY && waiver.decision != Status.DENY && !policy.allowRepoOverrideOfDenied) {
            return WaiverDecision(status = status, waiver = null)
        }
        return WaiverDecision(status = waiver.decision, waiver = waiver)
    }

    private fun rawLicenseStatements(component: JSONObject): List<LicenseStatement> {
        val licenses = component.optJSONArray("licenses") ?: return emptyList()
        return buildList {
            repeat(licenses.length()) { index ->
                val entry = licenses.optJSONObject(index) ?: return@repeat
                val license = entry.optJSONObject("license")
                val expression = entry.optString("expression").trim()
                when {
                    expression.isNotEmpty() -> add(LicenseStatement.fromExpression(expression))
                    license != null -> {
                        val id = license.optString("id").trim()
                        val name = license.optString("name").trim()
                        listOf(id, name).filter { it.isNotEmpty() }.distinct().forEach { value -> add(LicenseStatement.single(value)) }
                    }
                }
            }
        }
    }

    private fun componentCoordinate(component: JSONObject): String? =
        component.optString("purl").takeIf { it.isNotBlank() }
            ?: run {
                val group = component.optString("group")
                val name = component.optString("name")
                val version = component.optString("version")
                if (name.isBlank()) null else listOf(group, name, version).filter { it.isNotBlank() }.joinToString(":")
            }

    private fun loadWaivers(repoPath: java.nio.file.Path, warnings: MutableList<String>): List<Waiver> {
        val waiverPath = repoPath.resolve(policy.repoPolicyFile)
        if (!waiverPath.exists()) return emptyList()

        val map = loadMap(waiverPath)
        return listValue(map["waivers"]).mapNotNull { waiverEntry ->
            val waiverMap = mapValue(waiverEntry)
            val waiver = Waiver(
                `package` = waiverMap["package"]?.toString() ?: error("Waiver in $waiverPath is missing package"),
                license = waiverMap["license"]?.toString(),
                decision = Status.valueOf(waiverMap["decision"]?.toString()?.uppercase() ?: error("Waiver in $waiverPath is missing decision")),
                owner = waiverMap["owner"]?.toString() ?: error("Waiver in $waiverPath is missing owner"),
                reason = waiverMap["reason"]?.toString() ?: error("Waiver in $waiverPath is missing reason"),
                expires = LocalDate.parse(waiverMap["expires"]?.toString() ?: error("Waiver in $waiverPath is missing expires")),
            )
            if (waiver.expires.isBefore(LocalDate.now())) {
                warnings += "Ignoring expired waiver in ${waiverPath.relativeTo(workspaceRoot)} for ${waiver.`package`} (${waiver.expires})"
                null
            } else {
                waiver
            }
        }
    }

    private fun computeOutcomeFingerprint(findings: List<Finding>, allowedLicenseCounts: Map<String, Int>): String = sha256(
        buildString {
            findings
                .sortedWith(compareBy<Finding> { it.status.rank }.thenBy { it.repo }.thenBy { it.component }.thenBy { it.license })
                .forEach { finding ->
                    append("${finding.status.name}|${finding.repo}|${finding.component}|${finding.license}|${finding.detail.orEmpty()}\n")
                }
            allowedLicenseCounts.toSortedMap().forEach { (license, count) ->
                append("ALLOW|$license|$count\n")
            }
        }
    )

    private fun loadCacheState(cachePath: java.nio.file.Path): CachedRepoState? {
        if (!cachePath.exists()) return null
        return runCatching {
            val json = JSONObject(cachePath.readText())
            CachedRepoState(
                schemaVersion = json.optInt("schemaVersion"),
                inputFingerprint = json.optString("inputFingerprint"),
                sbomFingerprint = json.optString("sbomFingerprint"),
                outcomeFingerprint = json.optString("outcomeFingerprint"),
                findings = json.optJSONArray("findings")?.let { findingsArray ->
                    buildList {
                        repeat(findingsArray.length()) { index ->
                            val finding = findingsArray.optJSONObject(index) ?: return@repeat
                            add(
                                Finding(
                                    status = Status.valueOf(finding.getString("status")),
                                    repo = finding.getString("repo"),
                                    component = finding.getString("component"),
                                    license = finding.getString("license"),
                                    detail = finding.optString("detail").takeIf { it.isNotBlank() },
                                )
                            )
                        }
                    }
                }.orEmpty(),
                allowedLicenseCounts = json.optJSONObject("allowedLicenseCounts")?.let { counts ->
                    counts.keys().asSequence().associateWith { key -> counts.optInt(key) }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    private fun writeCacheState(cachePath: java.nio.file.Path, state: CachedRepoState) {
        cachePath.parent.createDirectories()
        val json = JSONObject(
            mapOf(
                "schemaVersion" to state.schemaVersion,
                "generatedAt" to Instant.now().toString(),
                "inputFingerprint" to state.inputFingerprint,
                "sbomFingerprint" to state.sbomFingerprint,
                "outcomeFingerprint" to state.outcomeFingerprint,
                "allowedLicenseCounts" to JSONObject(state.allowedLicenseCounts),
                "findings" to JSONArray(
                    state.findings.map { finding ->
                        JSONObject(
                            mapOf(
                                "status" to finding.status.name,
                                "repo" to finding.repo,
                                "component" to finding.component,
                                "license" to finding.license,
                                "detail" to finding.detail,
                            )
                        )
                    }
                ),
            )
        )
        cachePath.outputStream().use { output -> output.writer().use { writer -> writer.write(json.toString(2) + "\n") } }
    }

    private fun printWarnings(warnings: List<String>) {
        warnings.sorted().forEach { warning -> out("WARN    $warning") }
        if (warnings.isNotEmpty()) out("")
    }

    private fun printAllowedLicenseCounts(allowedLicenseCounts: Map<String, Int>) {
        if (allowedLicenseCounts.isEmpty()) return
        out("Allowed licenses:")
        allowedLicenseCounts.toSortedMap().forEach { (license, count) ->
            out("- $license: $count")
        }
        out("")
    }

    private fun printFindings(findings: List<Finding>) {
        if (findings.isEmpty()) {
            out("License policy passed: no denied, review, or unknown licenses found.")
            return
        }

        findings.sortedWith(compareBy<Finding> { it.status.rank }.thenBy { it.repo }.thenBy { it.component }.thenBy { it.license })
            .forEach { finding ->
                val detailSuffix = finding.detail?.let { " [$it]" }.orEmpty()
                out("%-7s %-24s %-56s %s%s".format(finding.status.name, finding.repo, finding.component, finding.license, detailSuffix))
            }

        val byStatus = findings.groupBy { it.status }
        out("")
        out("License policy summary:")
        out("- denied: ${byStatus[Status.DENY]?.size ?: 0}")
        out("- review: ${byStatus[Status.REVIEW]?.size ?: 0}")
        out("- unknown: ${byStatus[Status.UNKNOWN]?.size ?: 0}")
    }

    private fun runCommand(workingDirectory: java.nio.file.Path, command: List<String>) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "Command failed (${exitCode}): ${command.joinToString(" ")}" }
    }

    private fun loadMap(path: java.nio.file.Path): Map<String, Any?> =
        path.inputStream().use { input ->
            @Suppress("UNCHECKED_CAST")
            (yaml.load<Any?>(input) as? Map<String, Any?>).orEmpty()
        }

    private fun listValue(value: Any?): List<Any?> = (value as? List<*>)?.toList().orEmpty()

    private fun mapValue(value: Any?): Map<Any?, Any?> = (value as? Map<*, *>)?.toMap().orEmpty()

    private fun sha256OfFiles(basePath: java.nio.file.Path, files: Collection<java.nio.file.Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { path -> path.toString() }.forEach { path ->
            digest.update(path.relativeTo(basePath).toString().toByteArray())
            digest.update(0)
            digest.update(path.readBytes())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal class LicenseClassifier(
    private val policy: LicensePolicy,
) {

    fun classify(statements: List<LicenseStatement>): LicenseEvaluation {
        if (statements.isEmpty()) {
            return LicenseEvaluation(
                status = Status.UNKNOWN,
                displayLicenses = emptyList(),
                allowedLicenses = emptyList(),
                reason = "no license metadata found",
                policyNotes = emptyList(),
            )
        }

        val evaluatedStatements = statements.map(::evaluateStatement)
        val overall = when {
            evaluatedStatements.any { it.status == Status.ALLOW } -> Status.ALLOW
            evaluatedStatements.any { it.status == Status.REVIEW } -> Status.REVIEW
            evaluatedStatements.any { it.status == Status.UNKNOWN } -> Status.UNKNOWN
            evaluatedStatements.any { it.status == Status.DENY } -> Status.DENY
            else -> Status.UNKNOWN
        }

        val displayLicenses = evaluatedStatements
            .flatMap { statement -> statement.displayLicenses }
            .ifEmpty { statements.flatMap { statement -> statement.licenses } }
            .distinct()
        val allowedLicenses = if (overall == Status.ALLOW) {
            evaluatedStatements.flatMap { statement -> statement.allowedLicenses }.distinct().sorted()
        } else {
            emptyList()
        }
        val reason = when (overall) {
            Status.DENY -> evaluatedStatements.firstOrNull { it.status == Status.DENY }?.reason
                ?: "license denied by workspace policy"
            Status.REVIEW -> evaluatedStatements.firstOrNull { it.status == Status.REVIEW }?.reason
                ?: "license requires manual review under workspace policy"
            Status.UNKNOWN -> evaluatedStatements.firstOrNull { it.status == Status.UNKNOWN }?.reason
                ?: "license not recognized by workspace policy"
            Status.ALLOW -> ""
        }
        val policyNotes = evaluatedStatements
            .flatMap { statement -> statement.policyNotes }
            .distinct()

        return LicenseEvaluation(
            status = overall,
            displayLicenses = displayLicenses,
            allowedLicenses = allowedLicenses,
            reason = reason,
            policyNotes = policyNotes,
        )
    }

    private fun evaluateStatement(statement: LicenseStatement): LicenseEvaluation {
        val normalized = statement.licenses.map(policy::normalizeLicense)
        val displayLicenses = normalized.map { normalizedValue -> normalizedValue.display }
        val statuses = normalized.map { normalizedValue -> policy.statusFor(normalizedValue) }
        val allowedLicenses = normalized
            .filter { normalizedValue -> policy.statusFor(normalizedValue) == Status.ALLOW }
            .map { normalizedValue -> normalizedValue.canonical ?: normalizedValue.display }
            .distinct()
        val policyNotes = normalized
            .mapNotNull(policy::noteFor)
            .distinct()

        val status = when (statement.operator) {
            LicenseOperator.AND -> classifyAnd(statuses)
            LicenseOperator.OR -> classifyOr(statuses)
            LicenseOperator.MIXED -> classifyMixed(statuses)
            LicenseOperator.SINGLE -> statuses.singleOrNull() ?: Status.UNKNOWN
        }
        val reason = when (statement.operator) {
            LicenseOperator.AND -> reasonForAnd(normalized, statuses)
            LicenseOperator.OR -> reasonForOr(normalized, statuses)
            LicenseOperator.MIXED -> reasonForMixed(normalized, statuses)
            LicenseOperator.SINGLE -> reasonForSingle(normalized.firstOrNull(), statuses.singleOrNull() ?: Status.UNKNOWN)
        }

        return LicenseEvaluation(
            status = status,
            displayLicenses = displayLicenses,
            allowedLicenses = if (status == Status.ALLOW) allowedLicenses else emptyList(),
            reason = reason,
            policyNotes = policyNotes,
        )
    }

    private fun classifyAnd(statuses: List<Status>) = when {
        statuses.isEmpty() -> Status.UNKNOWN
        statuses.any { it == Status.DENY } -> Status.DENY
        statuses.any { it == Status.REVIEW } -> Status.REVIEW
        statuses.any { it == Status.UNKNOWN } -> Status.UNKNOWN
        else -> Status.ALLOW
    }

    private fun classifyOr(statuses: List<Status>) = when {
        statuses.isEmpty() -> Status.UNKNOWN
        statuses.any { it == Status.ALLOW } -> Status.ALLOW
        statuses.any { it == Status.REVIEW } -> Status.REVIEW
        statuses.all { it == Status.DENY } -> Status.DENY
        statuses.any { it == Status.UNKNOWN } -> Status.UNKNOWN
        else -> Status.UNKNOWN
    }

    private fun classifyMixed(statuses: List<Status>) = when {
        statuses.isEmpty() -> Status.UNKNOWN
        statuses.any { it == Status.DENY } -> Status.REVIEW
        statuses.any { it == Status.REVIEW } -> Status.REVIEW
        statuses.any { it == Status.UNKNOWN } -> Status.UNKNOWN
        statuses.all { it == Status.ALLOW } -> Status.ALLOW
        else -> Status.UNKNOWN
    }

    private fun reasonForSingle(license: NormalizedLicense?, status: Status): String {
        val name = license?.display ?: "(missing)"
        return when (status) {
            Status.DENY -> "license '$name' is denied by workspace policy"
            Status.REVIEW -> "license '$name' is review-only in workspace policy"
            Status.UNKNOWN -> "license '$name' is not classified by workspace policy"
            Status.ALLOW -> ""
        }
    }

    private fun reasonForAnd(licenses: List<NormalizedLicense>, statuses: List<Status>): String = when {
        statuses.any { it == Status.DENY } -> {
            val denied = licenses.zip(statuses).filter { it.second == Status.DENY }.joinToString(", ") { "'${it.first.display}'" }
            "AND expression includes denied license ${denied}"
        }
        statuses.any { it == Status.REVIEW } -> {
            val review = licenses.zip(statuses).filter { it.second == Status.REVIEW }.joinToString(", ") { "'${it.first.display}'" }
            "AND expression includes review-only license ${review}"
        }
        statuses.any { it == Status.UNKNOWN } -> {
            val unknown = licenses.zip(statuses).filter { it.second == Status.UNKNOWN }.joinToString(", ") { "'${it.first.display}'" }
            "AND expression includes unclassified license ${unknown}"
        }
        else -> ""
    }

    private fun reasonForOr(licenses: List<NormalizedLicense>, statuses: List<Status>): String = when {
        statuses.any { it == Status.ALLOW } -> ""
        statuses.any { it == Status.REVIEW } -> {
            val review = licenses.zip(statuses).filter { it.second == Status.REVIEW }.joinToString(", ") { "'${it.first.display}'" }
            "OR expression has no allowed branch and includes review-only license ${review}"
        }
        statuses.all { it == Status.DENY } -> {
            val denied = licenses.joinToString(", ") { "'${it.display}'" }
            "all OR branches are denied: ${denied}"
        }
        statuses.any { it == Status.UNKNOWN } -> {
            val unknown = licenses.zip(statuses).filter { it.second == Status.UNKNOWN }.joinToString(", ") { "'${it.first.display}'" }
            "OR expression has no allowed branch and includes unclassified license ${unknown}"
        }
        else -> ""
    }

    private fun reasonForMixed(licenses: List<NormalizedLicense>, statuses: List<Status>): String = when {
        statuses.any { it == Status.DENY } -> {
            val denied = licenses.zip(statuses).filter { it.second == Status.DENY }.joinToString(", ") { "'${it.first.display}'" }
            "mixed AND/OR license expression requires review because it includes denied license ${denied}"
        }
        statuses.any { it == Status.REVIEW } -> {
            val review = licenses.zip(statuses).filter { it.second == Status.REVIEW }.joinToString(", ") { "'${it.first.display}'" }
            "mixed AND/OR license expression requires review because it includes review-only license ${review}"
        }
        statuses.any { it == Status.UNKNOWN } -> {
            val unknown = licenses.zip(statuses).filter { it.second == Status.UNKNOWN }.joinToString(", ") { "'${it.first.display}'" }
            "mixed AND/OR license expression includes unclassified license ${unknown}"
        }
        statuses.any { it == Status.ALLOW } -> ""
        else -> ""
    }
}

internal class MavenLicenseResolver(
    workspaceRoot: java.nio.file.Path,
) {
    private val cachePath = workspaceRoot.resolve(".workspace-run-state/license-audit/maven-license-cache.json")
    private val fetchedPomRoot = workspaceRoot.resolve(".workspace-run-state/license-audit/poms")
    private val cache = linkedMapOf<String, MavenLicenseCacheEntry>()
    private var dirty = false
    private val localPomRoots = buildList {
        add(Path(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1"))
        addAll(
            defaultRepos.map { repo ->
                workspaceRoot.resolve(repo).resolve(".gradle-verification-home/caches/modules-2/files-2.1")
            }
        )
    }.distinct().filter { it.exists() && it.isDirectory() }

    private val repositories = listOf(
        "https://repo.maven.apache.org/maven2",
        "https://plugins.gradle.org/m2",
        "https://packages.confluent.io/maven",
    )

    init {
        loadCache()
    }

    fun resolve(component: JSONObject): MavenLicenseResolution {
        val coordinate = component.optString("purl").takeIf { it.startsWith("pkg:maven/") }?.let(::parsePurl)
            ?: MavenCoordinate(
                group = component.optString("group"),
                artifact = component.optString("name"),
                version = component.optString("version"),
            ).takeIf { it.group.isNotBlank() && it.artifact.isNotBlank() && it.version.isNotBlank() }
            ?: return MavenLicenseResolution(emptyList(), "not-maven")

        return resolveCoordinate(coordinate, linkedSetOf())
    }

    fun flush() {
        if (!dirty) return
        cachePath.parent.createDirectories()
        val json = JSONObject(
            mapOf(
                "schemaVersion" to MAVEN_LICENSE_CACHE_SCHEMA_VERSION,
                "entries" to JSONObject(
                    cache.entries.associate { (key, value) ->
                        key to JSONObject(
                            mapOf(
                                "licenses" to JSONArray(value.licenses),
                                "source" to value.source,
                            )
                        )
                    }
                )
            )
        )
        cachePath.outputStream().use { output -> output.writer().use { writer -> writer.write(json.toString(2) + "\n") } }
        dirty = false
    }

    private fun resolveCoordinate(coordinate: MavenCoordinate, visited: MutableSet<String>): MavenLicenseResolution {
        val key = coordinate.key
        if (!visited.add(key)) return MavenLicenseResolution(emptyList(), "cycle")
        cache[key]?.let { cached ->
            if (cached.licenses.isNotEmpty() || cached.source !in setOf("missing", "parent-missing")) {
                return MavenLicenseResolution(cached.licenses, cached.source)
            }
        }

        val pomPath = findLocalPom(coordinate) ?: fetchPom(coordinate)
        if (pomPath == null || !pomPath.exists()) {
            remember(key, emptyList(), source = "missing")
            return MavenLicenseResolution(emptyList(), "missing")
        }

        val pomText = pomPath.readText()
        val directLicenses = extractLicenseNames(pomText)
        if (directLicenses.isNotEmpty()) {
            remember(key, directLicenses, source = "pom")
            return MavenLicenseResolution(directLicenses, "pom")
        }

        val parent = extractParentCoordinate(pomText)
        if (parent != null) {
            val inherited = resolveCoordinate(parent, visited)
            remember(key, inherited.licenses, source = if (inherited.licenses.isEmpty()) "parent-missing" else "parent")
            return inherited
        }

        remember(key, emptyList(), source = "unlicensed")
        return MavenLicenseResolution(emptyList(), "unlicensed")
    }

    private fun remember(key: String, licenses: List<String>, source: String) {
        val previous = cache[key]
        val next = MavenLicenseCacheEntry(licenses = licenses, source = source)
        if (previous != next) {
            cache[key] = next
            dirty = true
        }
    }

    private fun loadCache() {
        if (!cachePath.exists()) return
        runCatching {
            val json = JSONObject(cachePath.readText())
            if (json.optInt("schemaVersion") != MAVEN_LICENSE_CACHE_SCHEMA_VERSION) {
                cache.clear()
                dirty = true
                return@runCatching
            }
            val entries = json.optJSONObject("entries") ?: return@runCatching
            entries.keys().forEach { key ->
                val entry = entries.optJSONObject(key) ?: return@forEach
                val licenses = buildList {
                    val array = entry.optJSONArray("licenses") ?: JSONArray()
                    repeat(array.length()) { index ->
                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                cache[key] = MavenLicenseCacheEntry(licenses = licenses, source = entry.optString("source"))
            }
        }
    }

    private fun parsePurl(purl: String): MavenCoordinate? {
        val withoutPrefix = purl.removePrefix("pkg:maven/")
        val nameAndVersion = withoutPrefix.substringAfter("/", missingDelimiterValue = return null)
        val group = withoutPrefix.substringBefore("/")
        val artifact = nameAndVersion.substringBefore("@")
        val version = nameAndVersion.substringAfter("@", missingDelimiterValue = "")
        return MavenCoordinate(group = group, artifact = artifact, version = version).takeIf {
            it.group.isNotBlank() && it.artifact.isNotBlank() && it.version.isNotBlank()
        }
    }

    private fun findLocalPom(coordinate: MavenCoordinate): java.nio.file.Path? {
        val relative = Path(coordinate.group, coordinate.artifact, coordinate.version)
        localPomRoots.forEach { root ->
            val versionDir = root.resolve(relative)
            if (!versionDir.exists() || !versionDir.isDirectory()) return@forEach
            versionDir.walk().firstOrNull { path ->
                path.name.endsWith(".pom")
            }?.let { return it }
        }
        return null
    }

    private fun fetchPom(coordinate: MavenCoordinate): java.nio.file.Path? {
        val cachedPom = fetchedPomRoot
            .resolve(coordinate.group.replace('.', '/'))
            .resolve(coordinate.artifact)
            .resolve(coordinate.version)
            .resolve("${coordinate.artifact}-${coordinate.version}.pom")
        if (cachedPom.exists()) return cachedPom

        val groupPath = coordinate.group.replace('.', '/')
        repositories.forEach { repository ->
            val url = "${repository.trimEnd('/')}/$groupPath/${coordinate.artifact}/${coordinate.version}/${coordinate.artifact}-${coordinate.version}.pom"
            cachedPom.parent.createDirectories()
            val process = runCatching {
                ProcessBuilder(
                    "curl",
                    "--fail",
                    "--location",
                    "--silent",
                    "--show-error",
                    "--max-time",
                    "15",
                    "--output",
                    cachedPom.toString(),
                    url,
                ).start()
            }.getOrNull() ?: return@forEach
            val exitCode = process.waitFor()
            if (exitCode == 0 && cachedPom.exists()) {
                return cachedPom
            }
            cachedPom.toFile().delete()
        }
        return null
    }

    private fun extractLicenseNames(pomText: String): List<String> =
        Regex("(?s)<license>\\s*.*?<name>\\s*([^<]+?)\\s*</name>.*?</license>")
            .findAll(pomText)
            .map { match -> match.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    private fun extractParentCoordinate(pomText: String): MavenCoordinate? {
        val match = Regex(
            "(?s)<parent>\\s*.*?<groupId>\\s*([^<]+?)\\s*</groupId>\\s*.*?<artifactId>\\s*([^<]+?)\\s*</artifactId>\\s*.*?<version>\\s*([^<]+?)\\s*</version>.*?</parent>"
        ).find(pomText) ?: return null
        return MavenCoordinate(
            group = match.groupValues[1].trim(),
            artifact = match.groupValues[2].trim(),
            version = match.groupValues[3].trim(),
        )
    }
}

internal data class LicensePolicy(
    val allowed: Set<String>,
    val review: Set<String>,
    val denied: Set<String>,
    val aliases: Map<String, String>,
    val licenseNotes: Map<String, String>,
    val packageOverrides: List<PackageOverride>,
    val internalGroups: List<String>,
    val repoPolicyFile: String,
    val allowRepoOverrideOfDenied: Boolean,
) {

    private val normalizedAliases = aliases.mapKeys { (key, _) -> normalizeAliasKey(key) }

    fun normalizeLicense(rawValue: String): NormalizedLicense {
        val candidate = rawValue.trim()
        if (candidate.isEmpty()) return NormalizedLicense(display = "(missing)", canonical = null)

        val canonical = when {
            aliases.containsKey(candidate) -> aliases.getValue(candidate)
            normalizedAliases.containsKey(normalizeAliasKey(candidate)) -> normalizedAliases.getValue(normalizeAliasKey(candidate))
            candidate in allowed || candidate in review || candidate in denied -> candidate
            candidate.startsWith("LicenseRef-") -> candidate
            looksLikeLicenseIdentifier(candidate) -> candidate
            else -> null
        }

        return NormalizedLicense(display = canonical ?: candidate, canonical = canonical)
    }

    fun statusFor(license: NormalizedLicense): Status = when (val canonical = license.canonical) {
        null -> if (looksProprietary(license.display)) Status.DENY else Status.UNKNOWN
        in denied -> Status.DENY
        in allowed -> Status.ALLOW
        in review -> Status.REVIEW
        else -> if (looksProprietary(canonical)) Status.DENY else Status.UNKNOWN
    }

    fun isInternalComponent(component: JSONObject, coordinate: String): Boolean {
        val group = component.optString("group")
        val purl = component.optString("purl")
        return internalGroups.any { prefix ->
            group == prefix || group.startsWith("$prefix.") || coordinate.startsWith("$prefix:") || purl.contains("/$prefix/")
        }
    }

    fun packageOverrideFor(coordinate: String): PackageOverride? = packageOverrides.firstOrNull { override ->
        override.`package` == coordinate || override.packagePrefix?.let(coordinate::startsWith) == true
    }

    fun noteFor(license: NormalizedLicense): String? {
        val key = license.canonical ?: return null
        return licenseNotes[key]
    }

    private fun looksLikeLicenseIdentifier(value: String) = value.matches(Regex("[A-Za-z0-9.+-]+"))

    private fun normalizeAliasKey(value: String) = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

    private fun looksProprietary(value: String): Boolean {
        val normalized = value.lowercase()
        return listOf("proprietary", "commercial", "eula", "all rights reserved", "license agreement").any(normalized::contains)
    }

    companion object {
        fun from(map: Map<String, Any?>) = LicensePolicy(
            allowed = stringSet(map["allow"]),
            review = stringSet(map["review"]),
            denied = stringSet(map["deny"]),
            aliases = stringMap(map["aliases"]),
            licenseNotes = stringMap(map["license_notes"]),
            packageOverrides = packageOverrideList(map["package_overrides"]),
            internalGroups = stringList(map["internal_groups"]),
            repoPolicyFile = mapValue(map["waiver_policy"])["repo_policy_file"]?.toString() ?: "license-waivers.yml",
            allowRepoOverrideOfDenied = mapValue(map["waiver_policy"])["allow_repo_override_of_denied"] == true,
        )

        private fun stringSet(value: Any?) = stringList(value).toSet()

        private fun stringList(value: Any?) = listValue(value).mapNotNull { it as? String }

        private fun stringMap(value: Any?) =
            mapValue(value).mapNotNull { (key, entryValue) ->
                val stringKey = key as? String ?: return@mapNotNull null
                val stringValue = entryValue as? String ?: return@mapNotNull null
                stringKey to stringValue
            }.toMap()

        private fun packageOverrideList(value: Any?): List<PackageOverride> =
            listValue(value).mapNotNull { entry ->
                val overrideMap = mapValue(entry)
                val pkg = overrideMap["package"] as? String
                val packagePrefix = overrideMap["package_prefix"] as? String
                if (pkg == null && packagePrefix == null) return@mapNotNull null
                val license = overrideMap["license"] as? String ?: return@mapNotNull null
                val reason = overrideMap["reason"] as? String ?: return@mapNotNull null
                PackageOverride(`package` = pkg, packagePrefix = packagePrefix, license = license, reason = reason)
            }

        private fun listValue(value: Any?) = (value as? List<*>)?.toList().orEmpty()

        private fun mapValue(value: Any?) = (value as? Map<*, *>)?.toMap().orEmpty()
    }
}

internal data class RepoSbom(
    val path: java.nio.file.Path,
    val document: JSONObject,
)

internal data class RepoAuditResult(
    val findings: List<Finding>,
    val allowedLicenseCounts: Map<String, Int>,
)

internal data class CachedRepoState(
    val schemaVersion: Int,
    val inputFingerprint: String,
    val sbomFingerprint: String,
    val outcomeFingerprint: String,
    val findings: List<Finding>,
    val allowedLicenseCounts: Map<String, Int>,
)

internal data class RepoEvaluation(
    val findings: List<Finding>,
    val allowedLicenseCounts: Map<String, Int>,
)

internal data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    val key: String = "$group:$artifact:$version"
}

internal data class MavenLicenseCacheEntry(
    val licenses: List<String>,
    val source: String,
)

internal data class MavenLicenseResolution(
    val licenses: List<String>,
    val source: String,
    val detail: String? = null,
)

internal data class PackageOverride(
    val `package`: String? = null,
    val packagePrefix: String? = null,
    val license: String,
    val reason: String,
)

internal data class Waiver(
    val `package`: String,
    val license: String?,
    val decision: Status,
    val owner: String,
    val reason: String,
    val expires: LocalDate,
)

internal data class Finding(
    val status: Status,
    val repo: String,
    val component: String,
    val license: String,
    val detail: String? = null,
)

internal data class LicenseEvaluation(
    val status: Status,
    val displayLicenses: List<String>,
    val allowedLicenses: List<String>,
    val reason: String,
    val policyNotes: List<String>,
)

internal data class ComponentAuditResult(
    val finding: Finding?,
    val allowedLicenses: List<String>,
)

internal data class WaiverDecision(
    val status: Status,
    val waiver: Waiver?,
)

internal data class NormalizedLicense(
    val display: String,
    val canonical: String?,
)

internal data class LicenseStatement(
    val operator: LicenseOperator,
    val licenses: List<String>,
) {
    companion object {
        fun single(license: String) = LicenseStatement(operator = LicenseOperator.SINGLE, licenses = listOf(license))

        fun fromExpression(expression: String): LicenseStatement {
            val trimmed = expression.trim().removeSurrounding("(", ")")
            val hasAnd = Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            val hasOr = Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            val licenses = trimmed
                .replace("(", " ")
                .replace(")", " ")
                .split(Regex("\\s+(?:AND|OR)\\s+", RegexOption.IGNORE_CASE))
                .map { value -> value.trim() }
                .filter { value -> value.isNotEmpty() }
                .map { value -> value.split(Regex("\\s+WITH\\s+", RegexOption.IGNORE_CASE), limit = 2).first() }

            val operator = when {
                hasAnd && hasOr -> LicenseOperator.MIXED
                hasAnd -> LicenseOperator.AND
                hasOr -> LicenseOperator.OR
                else -> LicenseOperator.SINGLE
            }
            return LicenseStatement(operator = operator, licenses = licenses)
        }
    }
}

internal enum class LicenseOperator {
    SINGLE,
    AND,
    OR,
    MIXED,
}

internal enum class Status(val rank: Int) {
    DENY(0),
    REVIEW(1),
    UNKNOWN(2),
    ALLOW(3),
}
