package com.example.fee

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Enforces AGENTS.md's "every production class gets a test" (task 7.13). A production file `X.kt` needs `XTest.kt` in
 * the same package, or an `XIT.kt`, or an entry in [coveredElsewhere] naming the tests that exercise it and why it has
 * no test of its own. Reads the source trees, so it runs with the project directory as working directory (Surefire's
 * `basedir`).
 */
class TestCoverageAuditTest {

    private data class CoveredElsewhere(val symbol: String, val tests: List<String>, val reason: String)

    // Keyed by the production file's path below src/main/kotlin/com/example/fee. Each listed test must exist and name
    // the symbol, so an entry can't silently outlive the test it points to.
    private val coveredElsewhere = mapOf(
        "domain/Transaction.kt" to CoveredElsewhere(
            symbol = "Transaction",
            tests = listOf("domain/TransactionMapperTest.kt"),
            reason = "a data class without logic; the mapper test asserts every field it holds",
        ),
        "restate/FeeWorkflowClients.kt" to CoveredElsewhere(
            symbol = "FeeWorkflowClients",
            tests = listOf("restate/RestateConfigTest.kt", "application/FeeServiceTest.kt"),
            reason = "a fun interface; RestateConfig's factory bean is tested for service, key and handler",
        ),
        "restate/TransactionFeeRecordClients.kt" to CoveredElsewhere(
            symbol = "TransactionFeeRecordClients",
            tests = listOf("restate/RestateConfigTest.kt", "application/FeeServiceTest.kt"),
            reason = "a fun interface; RestateConfig's factory bean is tested for service, key and handler",
        ),
        "restate/RestateAdminTypes.kt" to CoveredElsewhere(
            symbol = "ServiceDescription",
            tests = listOf("restate/RestateAdminClientTest.kt", "restate/RestateRegistrarTest.kt"),
            reason = "response types only; the client test reads Restate 1.8.1's real JSON, extra fields included",
        ),
        "fee/FeeRule.kt" to CoveredElsewhere(
            symbol = "FeeRule",
            tests = listOf("fee/FeeCalculatorTest.kt"),
            reason = "an extension-point interface; every implementation has its own test",
        ),
        "restate/StepHook.kt" to CoveredElsewhere(
            symbol = "StepHook",
            tests = listOf("restate/FeeWorkflowTest.kt", "restate/NoOpStepHookTest.kt"),
            reason = "an interface; the workflow test checks the calls around every step, CrashRecoveryIT the crashes",
        ),
        "validation/TransactionValidator.kt" to CoveredElsewhere(
            symbol = "TransactionValidator",
            tests = listOf("validation/RequestValidatorTest.kt"),
            reason = "an extension-point interface; every implementation has its own test",
        ),
        "wallet/WalletGateway.kt" to CoveredElsewhere(
            symbol = "WalletGateway",
            tests = listOf("restate/FeeWorkflowTest.kt"),
            reason = "an interface; FakeWalletGatewayTest tests the implementation, the ITs a programmable one",
        ),
    )

    private val baseDir: Path = Path.of(System.getProperty("basedir") ?: ".")
    private val mainRoot = baseDir.resolve("src/main/kotlin/com/example/fee")
    private val testRoot = baseDir.resolve("src/test/kotlin/com/example/fee")

    private fun kotlinFiles(root: Path): List<String> =
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .map { it.relativeTo(root).invariantSeparatorsPathString }
                .toList()
        }.sorted()

    private fun hasOwnTest(productionFile: String, testFiles: List<String>): Boolean {
        val name = Path.of(productionFile).nameWithoutExtension
        val ownTest = productionFile.removeSuffix(".kt") + "Test.kt"
        return ownTest in testFiles || testFiles.any { Path.of(it).fileName.toString() == "${name}IT.kt" }
    }

    @Test
    fun `every production file has its own test or a justified entry naming the tests that cover it`() {
        // given
        val productionFiles = kotlinFiles(mainRoot)
        val testFiles = kotlinFiles(testRoot)

        // when
        val uncovered = productionFiles.filterNot { hasOwnTest(it, testFiles) || it in coveredElsewhere }

        // then
        assertThat(productionFiles).isNotEmpty()
        assertThat(uncovered)
            .withFailMessage {
                "Production files without a test (add <Name>Test.kt in the same package, or a justified entry in " +
                    "coveredElsewhere): $uncovered"
            }
            .isEmpty()
    }

    @Test
    fun `every covered-elsewhere entry points to existing tests that name the class`() {
        // given
        val entries = coveredElsewhere

        // when
        val broken = entries.flatMap { (productionFile, entry) ->
            val symbol = Regex("\\b${Regex.escape(entry.symbol)}\\b", RegexOption.IGNORE_CASE)
            entry.tests.mapNotNull { test ->
                val path = testRoot.resolve(test)
                when {
                    !path.exists() -> "$productionFile: $test does not exist"
                    !symbol.containsMatchIn(path.readText()) -> "$productionFile: $test never names ${entry.symbol}"
                    else -> null
                }
            }
        }

        // then
        assertThat(broken).isEmpty()
        assertThat(entries.values).allSatisfy { assertThat(it.reason).isNotBlank() }
    }

    @Test
    fun `covered-elsewhere lists only existing production files that have no test of their own`() {
        // given
        val productionFiles = kotlinFiles(mainRoot)
        val testFiles = kotlinFiles(testRoot)

        // when
        val stale = coveredElsewhere.keys.filter { it !in productionFiles || hasOwnTest(it, testFiles) }

        // then
        assertThat(stale)
            .withFailMessage { "Remove these coveredElsewhere entries (file gone or now has its own test): $stale" }
            .isEmpty()
    }
}
