package com.example.fee.wallet

import com.example.fee.config.FakeWalletProperties
import com.example.fee.domain.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
class FakeWalletGatewayTest {

    private fun gateway(delay: Duration = Duration.ZERO, pause: FakeWalletGateway.Pause = FakeWalletGateway.Pause {}) =
        FakeWalletGateway(FakeWalletProperties(delay), pause)

    @Test
    fun `charges once and returns a receipt with the key, amount and asset`() {
        // given
        val wallet = gateway()

        // when
        val receipt = wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(receipt.idempotencyKey).isEqualTo("fee-txn_001")
        assertThat(receipt.amount).isEqualByComparingTo("1.50")
        assertThat(receipt.asset).isEqualTo(Asset.USD)
        assertThat(receipt.chargeId).isNotBlank()
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `a repeated key returns the first receipt without charging again`() {
        // given
        val wallet = gateway()
        val first = wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // when
        val second = wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(second).isEqualTo(first)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `a repeated key with another amount still returns the first receipt`() {
        // given
        val wallet = gateway()
        val first = wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // when
        val second = wallet.charge("fee-txn_001", BigDecimal("9.99"), Asset.EUR)

        // then
        assertThat(second).isEqualTo(first)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `different keys are charged separately`() {
        // given
        val wallet = gateway()

        // when
        val first = wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)
        val second = wallet.charge("fee-txn_002", BigDecimal("2.00"), Asset.EUR)

        // then
        assertThat(second.chargeId).isNotEqualTo(first.chargeId)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
        assertThat(wallet.chargeCount("fee-txn_002")).isEqualTo(1)
    }

    @Test
    fun `charge count is 0 for a key that was never charged`() {
        // given
        val wallet = gateway()
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // when
        val count = wallet.chargeCount("fee-txn_999")

        // then
        assertThat(count).isZero()
    }

    @Test
    fun `concurrent calls with the same key charge once`() {
        // given
        val callers = 8
        val start = CountDownLatch(1)
        val wallet = gateway(Duration.ofMillis(1)) { start.await(5, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(callers)

        // when
        val receipts = try {
            val futures = (1..callers).map {
                executor.submit<ChargeReceipt> { wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD) }
            }
            start.countDown()
            futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // then
        assertThat(receipts.toSet()).hasSize(1)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `waits the configured delay before it records the charge`() {
        // given
        val countsDuringPause = mutableListOf<Int>()
        val pauses = mutableListOf<Duration>()
        lateinit var wallet: FakeWalletGateway
        wallet = gateway(Duration.ofSeconds(5)) { delay ->
            pauses += delay
            countsDuringPause += wallet.chargeCount("fee-txn_001")
        }

        // when
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(pauses).containsExactly(Duration.ofSeconds(5))
        assertThat(countsDuringPause).containsExactly(0)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `a failure during the delay leaves nothing charged`() {
        // given
        val wallet = gateway(Duration.ofSeconds(5)) { throw InterruptedException("endpoint stopped") }

        // when
        val failure = runCatching { wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD) }.exceptionOrNull()

        // then
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(wallet.chargeCount("fee-txn_001")).isZero()
    }

    @Test
    fun `does not wait when the delay is 0`() {
        // given
        val pauses = mutableListOf<Duration>()
        val wallet = gateway(Duration.ZERO) { pauses += it }

        // when
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(pauses).isEmpty()
    }

    @Test
    fun `does not wait again for a key that is already charged`() {
        // given
        val pauses = mutableListOf<Duration>()
        val wallet = gateway(Duration.ofSeconds(5)) { pauses += it }
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // when
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(pauses).hasSize(1)
    }

    @Test
    fun `by default really sleeps for the configured delay`() {
        // given
        val delay = Duration.ofMillis(50)
        val wallet = FakeWalletGateway(FakeWalletProperties(delay))
        val started = System.nanoTime()

        // when
        wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(delay)
        assertThat(wallet.chargeCount("fee-txn_001")).isEqualTo(1)
    }

    @Test
    fun `logs the wait and the charge with key, amount, asset and receipt`(output: CapturedOutput) {
        // given
        val wallet = gateway(Duration.ofSeconds(20))

        // when
        val receipt = wallet.charge("fee-txn_002", BigDecimal("2.00"), Asset.EUR)

        // then
        assertThat(output.out)
            .contains("Fake wallet: waiting PT20S before charging fee-txn_002 2.00 EUR")
            .contains("Fake wallet: charged fee-txn_002 2.00 EUR as ${receipt.chargeId}")
    }

    @Test
    fun `logs one charge per key and no wait when the delay is 0`(output: CapturedOutput) {
        // given
        val wallet = gateway()

        // when
        wallet.charge("fee-txn_003", BigDecimal("1.50"), Asset.USD)
        wallet.charge("fee-txn_003", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(output.out.lines().filter { "Fake wallet: charged fee-txn_003 " in it }).hasSize(1)
        assertThat(output.out).doesNotContain("Fake wallet: waiting")
    }

    @Test
    fun `a failure during the wait logs no charge`(output: CapturedOutput) {
        // given
        val wallet = gateway(Duration.ofSeconds(20)) { throw InterruptedException("killed") }

        // when
        runCatching { wallet.charge("fee-txn_004", BigDecimal("1.50"), Asset.USD) }

        // then
        assertThat(output.out).contains("Fake wallet: waiting PT20S before charging fee-txn_004")
        assertThat(output.out).doesNotContain("Fake wallet: charged fee-txn_004")
    }
}
