package sollecitom.tools.license_audit.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS

@TestInstance(PER_CLASS)
class LicenseClassifierTests {

    private val classifier = LicenseClassifier(
        LicensePolicy(
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
    )

    @Test
    fun `an OR expression with an allowed branch is allowed`() {

        val classification = classifier.classify(listOf(LicenseStatement.fromExpression("GPL-3.0-only OR Apache-2.0")))

        assertThat(classification.status).isEqualTo(Status.ALLOW)
    }

    @Test
    fun `an AND expression containing a denied license is denied`() {

        val classification = classifier.classify(listOf(LicenseStatement.fromExpression("Apache-2.0 AND GPL-3.0-only")))

        assertThat(classification.status).isEqualTo(Status.DENY)
    }

    @Test
    fun `proprietary license text is denied`() {

        val classification = classifier.classify(listOf(LicenseStatement.single("Commercial License Agreement")))

        assertThat(classification.status).isEqualTo(Status.DENY)
    }

    @Test
    fun `aliases are normalised before reporting`() {

        val classification = classifier.classify(listOf(LicenseStatement.single("MIT License")))

        assertThat(classification.displayLicenses).isEqualTo(listOf("MIT"))
    }

    @Test
    fun `mixed AND and OR expressions require review`() {

        val classification = classifier.classify(listOf(LicenseStatement.fromExpression("(Apache-2.0 OR MIT) AND GPL-3.0-only")))

        assertThat(classification.status).isEqualTo(Status.REVIEW)
    }

    @Test
    fun `multiple license entries are allowed when at least one is allowed`() {

        val classification = classifier.classify(listOf(LicenseStatement.single("LGPL-2.1-or-later"), LicenseStatement.single("Apache-2.0")))

        assertThat(classification.status).isEqualTo(Status.ALLOW)
    }
}
