package sollecitom.tools.license_audit.app

fun main() {
    val policy = LicensePolicy(
        allowed = setOf("Apache-2.0", "MIT"),
        review = setOf("MPL-2.0", "LicenseRef-Internal-Review"),
        denied = setOf("GPL-3.0-only"),
        aliases = mapOf("Apache 2.0" to "Apache-2.0", "MIT License" to "MIT"),
        packageOverrides = emptyMap(),
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

    println("License audit self-checks passed.")
}
