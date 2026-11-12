package com.example.fee.fee

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource

/**
 * Binds the `fee.*` sections to the `*FeeProperties` classes the way Spring does at startup (`bindOrCreate`, constructor
 * binding), without a Spring context.
 */
class FeePropertiesBindingTest {

    private fun binderFor(resource: Resource): Binder {
        val sources = YamlPropertySourceLoader().load("application.yml", resource)
        return Binder(ConfigurationPropertySources.from(sources))
    }

    private fun binderFor(yaml: String): Binder = binderFor(ByteArrayResource(yaml.trimIndent().toByteArray()))

    private val packaged: Binder by lazy { binderFor(ClassPathResource("application.yml")) }

    @Test
    fun `packaged Mobile Top Up fee binds to the fee table's rate`() {
        // given / when
        val properties = packaged.bindOrCreate(MobileTopUpFeeProperties.PREFIX, MobileTopUpFeeProperties::class.java)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.0015")
    }

    @Test
    fun `packaged Bank Transfer fee binds to the fee table's rate and minimum`() {
        // given / when
        val properties = packaged.bindOrCreate(BankTransferFeeProperties.PREFIX, BankTransferFeeProperties::class.java)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.005")
        assertThat(properties.min).isEqualByComparingTo("1.00")
    }

    @Test
    fun `packaged Merchant Payment fee binds to the fee table's rate and fixed amount`() {
        // given / when
        val properties = packaged.bindOrCreate(MerchantPaymentFeeProperties.PREFIX, MerchantPaymentFeeProperties::class.java)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.01")
        assertThat(properties.fixed).isEqualByComparingTo("0.30")
    }

    @Test
    fun `packaged Cash Withdrawal fee binds to the fee table's fixed amount`() {
        // given / when
        val properties = packaged.bindOrCreate(CashWithdrawalFeeProperties.PREFIX, CashWithdrawalFeeProperties::class.java)

        // then
        assertThat(properties.fixed).isEqualByComparingTo("2.00")
    }

    @Test
    fun `packaged money values are quoted so they keep their scale`() {
        // given / when
        val bankTransfer = packaged.bindOrCreate(BankTransferFeeProperties.PREFIX, BankTransferFeeProperties::class.java)
        val cashWithdrawal = packaged.bindOrCreate(CashWithdrawalFeeProperties.PREFIX, CashWithdrawalFeeProperties::class.java)

        // then
        assertThat(bankTransfer.min.toPlainString()).isEqualTo("1.00")
        assertThat(cashWithdrawal.fixed.toPlainString()).isEqualTo("2.00")
    }

    @Test
    fun `an override file changes only the keys it sets`() {
        // given
        val binder = binderFor(
            """
            fee:
              bank-transfer:
                rate: "0.006"
                min: "1.50"
            """,
        )

        // when
        val properties = binder.bindOrCreate(BankTransferFeeProperties.PREFIX, BankTransferFeeProperties::class.java)

        // then
        assertThat(properties.rate).isEqualByComparingTo("0.006")
        assertThat(properties.min).isEqualByComparingTo("1.50")
    }

    @Test
    fun `a missing fee section fails binding with a message naming the key`() {
        // given
        val binder = binderFor(
            """
            fee:
              bank-transfer:
                rate: "0.005"
                min: "1.00"
            """,
        )

        // when / then
        assertThatThrownBy { binder.bindOrCreate(MobileTopUpFeeProperties.PREFIX, MobileTopUpFeeProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            .rootCause()
            .hasMessageStartingWith("fee.mobile-top-up.rate is missing")
    }

    @Test
    fun `a missing fee value fails binding with a message naming the key`() {
        // given
        val binder = binderFor(
            """
            fee:
              bank-transfer:
                rate: "0.005"
            """,
        )

        // when / then
        assertThatThrownBy { binder.bindOrCreate(BankTransferFeeProperties.PREFIX, BankTransferFeeProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .hasMessageStartingWith("fee.bank-transfer.min is missing")
    }

    @Test
    fun `an out of range fee value fails binding with a message naming the key and the value`() {
        // given
        val binder = binderFor(
            """
            fee:
              merchant-payment:
                rate: "1.5"
                fixed: "0.30"
            """,
        )

        // when / then
        assertThatThrownBy { binder.bindOrCreate(MerchantPaymentFeeProperties.PREFIX, MerchantPaymentFeeProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .hasMessage("fee.merchant-payment.rate must be in [0, 1), was 1.5")
    }

    @Test
    fun `a negative fee amount fails binding with a message naming the key and the value`() {
        // given
        val binder = binderFor(
            """
            fee:
              cash-withdrawal:
                fixed: "-2.00"
            """,
        )

        // when / then
        assertThatThrownBy { binder.bindOrCreate(CashWithdrawalFeeProperties.PREFIX, CashWithdrawalFeeProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .rootCause()
            .hasMessage("fee.cash-withdrawal.fixed must not be negative, was -2.00")
    }

    @Test
    fun `a fee value that is not a number fails binding with a message naming the key and the value`() {
        // given
        val binder = binderFor(
            """
            fee:
              cash-withdrawal:
                fixed: "two euros"
            """,
        )

        // when
        val failure = catchThrowable {
            binder.bindOrCreate(CashWithdrawalFeeProperties.PREFIX, CashWithdrawalFeeProperties::class.java)
        }

        // then
        assertThat(failure).isInstanceOf(BindException::class.java)
        val bindFailure = failure as BindException
        assertThat(bindFailure.name.toString()).isEqualTo("fee.cash-withdrawal.fixed")
        assertThat(bindFailure.property?.value).isEqualTo("two euros")
        assertThat(bindFailure).hasMessageContaining("fee.cash-withdrawal.fixed").hasMessageContaining("java.math.BigDecimal")
    }
}
