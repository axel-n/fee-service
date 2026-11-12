package com.example.fee.wallet

import com.example.fee.domain.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
class FakeWalletGatewayTest {

    @Test
    fun `charges once and returns a receipt with the key, amount and asset`() {
        // given
        val wallet = FakeWalletGateway()

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
        val wallet = FakeWalletGateway()
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
        val wallet = FakeWalletGateway()
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
        val wallet = FakeWalletGateway()

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
        val wallet = FakeWalletGateway()
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
        val wallet = FakeWalletGateway()
        val executor = Executors.newFixedThreadPool(callers)

        // when
        val receipts = try {
            val futures = (1..callers).map {
                executor.submit<ChargeReceipt> {
                    start.await(5, TimeUnit.SECONDS)
                    wallet.charge("fee-txn_001", BigDecimal("1.50"), Asset.USD)
                }
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
    fun `logs the charge with key, amount, asset and receipt`(output: CapturedOutput) {
        // given
        val wallet = FakeWalletGateway()

        // when
        val receipt = wallet.charge("fee-txn_002", BigDecimal("2.00"), Asset.EUR)

        // then
        assertThat(output.out).contains("Fake wallet: charged fee-txn_002 2.00 EUR as ${receipt.chargeId}")
    }

    @Test
    fun `logs one charge per key`(output: CapturedOutput) {
        // given
        val wallet = FakeWalletGateway()

        // when
        wallet.charge("fee-txn_003", BigDecimal("1.50"), Asset.USD)
        wallet.charge("fee-txn_003", BigDecimal("1.50"), Asset.USD)

        // then
        assertThat(output.out.lines().filter { "Fake wallet: charged fee-txn_003 " in it }).hasSize(1)
    }
}
