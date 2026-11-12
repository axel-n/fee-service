package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

class FeeCalculatorTest {

    private val mobileTopUp = MobileTopUpFeeRule(MobileTopUpFeeProperties(BigDecimal("0.0015")))
    private val bankTransfer = BankTransferFeeRule(BankTransferFeeProperties(BigDecimal("0.005"), BigDecimal("1.00")))
    private val merchantPayment =
        MerchantPaymentFeeRule(MerchantPaymentFeeProperties(BigDecimal("0.01"), BigDecimal("0.30")))
    private val cashWithdrawal = CashWithdrawalFeeRule(CashWithdrawalFeeProperties(BigDecimal("2.00")))

    private val allRules: List<FeeRule> = listOf(mobileTopUp, bankTransfer, merchantPayment, cashWithdrawal)

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TransactionType::class, names = ["UNKNOWN"], mode = EnumSource.Mode.EXCLUDE)
    fun `the rule beans cover every transaction type except UNKNOWN`(type: TransactionType) {
        // given
        val calculator = FeeCalculator(allRules)

        // when
        val supported = calculator.supports(type)

        // then
        assertThat(supported).isTrue()
    }

    @Test
    fun `does not support UNKNOWN`() {
        // given
        val calculator = FeeCalculator(allRules)

        // when
        val supported = calculator.supports(TransactionType.UNKNOWN)

        // then
        assertThat(supported).isFalse()
    }

    @ParameterizedTest(name = "{0} on 1000 USD → {1}")
    @CsvSource(
        "MOBILE_TOP_UP,    1.5,  0.0015, Standard fee rate of 0.15%",
        "BANK_TRANSFER,    5.0,  0.005,  Fee rate of 0.5% (min 1.00 USD)",
        "MERCHANT_PAYMENT, 10.3, 0.01,   Fee rate of 1% plus 0.30 USD fixed",
        "CASH_WITHDRAWAL,  2.0,  0,      Flat fee of 2.00 USD",
    )
    fun `calculates with the rule of the transaction's type`(
        type: TransactionType,
        fee: String,
        rate: String,
        description: String,
    ) {
        // given
        val calculator = FeeCalculator(allRules)
        val tx = transaction("1000", Asset.USD, type)

        // when
        val quote = calculator.calculate(tx)

        // then
        assertThat(quote.fee).isEqualByComparingTo(fee)
        assertThat(quote.rate).isEqualByComparingTo(rate)
        assertThat(quote.description).isEqualTo(description)
    }

    @Test
    fun `refuses to calculate a type without a rule`() {
        // given
        val calculator = FeeCalculator(allRules)
        val tx = transaction("1000", Asset.USD, TransactionType.UNKNOWN)

        // when / then
        assertThatThrownBy { calculator.calculate(tx) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("No fee rule for transaction type UNKNOWN")
    }

    @Test
    fun `fails on a duplicate rule naming the type and both rules`() {
        // given
        val duplicate = FlatFee(TransactionType.MOBILE_TOP_UP, BigDecimal("1.00")) { "duplicate" }

        // when / then
        assertThatThrownBy { FeeCalculator(allRules + duplicate) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("exactly one fee rule")
            .hasMessageContaining("MOBILE_TOP_UP (MobileTopUpFeeRule, FlatFee)")
    }

    @Test
    fun `fails on a missing rule naming the type`() {
        // given
        val withoutBankTransfer = allRules - bankTransfer

        // when / then
        assertThatThrownBy { FeeCalculator(withoutBankTransfer) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Every transaction type needs a fee rule, but these have none: BANK_TRANSFER")
    }

    @Test
    fun `fails without any rule naming every type`() {
        // given
        val noRules = emptyList<FeeRule>()

        // when / then
        assertThatThrownBy { FeeCalculator(noRules) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MOBILE_TOP_UP, BANK_TRANSFER, MERCHANT_PAYMENT, CASH_WITHDRAWAL")
    }

    @Test
    fun `fails on a rule for UNKNOWN`() {
        // given
        val unknownRule = FlatFee(TransactionType.UNKNOWN, BigDecimal("1.00")) { "unknown" }

        // when / then
        assertThatThrownBy { FeeCalculator(allRules + unknownRule) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("A fee rule can't price UNKNOWN: FlatFee")
    }
}
