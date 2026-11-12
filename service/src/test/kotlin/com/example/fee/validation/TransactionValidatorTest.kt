package com.example.fee.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValidationOrderTest {

    private val structural = listOf(
        ValidationOrder.REQUIRED_FIELDS,
        ValidationOrder.TRANSACTION_ID,
        ValidationOrder.AMOUNT,
        ValidationOrder.CREATED_AT,
    )
    private val business = listOf(
        ValidationOrder.STATE,
        ValidationOrder.ASSET,
        ValidationOrder.ASSET_TYPE,
        ValidationOrder.TRANSACTION_TYPE,
        ValidationOrder.AMOUNT_SCALE,
    )

    @Test
    fun `orders the checks as the design lists them, spaced by 100`() {
        // given
        val all = structural + business

        // when
        val gaps = all.zipWithNext { earlier, later -> later - earlier }

        // then
        assertThat(all.first()).isEqualTo(100)
        assertThat(gaps).containsOnly(100)
    }

    @Test
    fun `runs every structural check before every business check`() {
        // given
        val lastStructural = structural.max()

        // when
        val firstBusiness = business.min()

        // then
        assertThat(lastStructural).isLessThan(firstBusiness)
    }

    @Test
    fun `checks the amount scale after the asset`() {
        // given
        val asset = ValidationOrder.ASSET

        // when
        val amountScale = ValidationOrder.AMOUNT_SCALE

        // then
        assertThat(amountScale).isGreaterThan(asset)
    }
}
