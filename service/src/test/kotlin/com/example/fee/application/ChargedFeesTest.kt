package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecords.response
import com.example.fee.record.InMemoryFeeRecordRepository
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration

class ChargedFeesTest {
    private val cache: Cache<String, FeeResponse> = Caffeine.newBuilder().build()

    private fun properties(lookupTimeout: Duration = Duration.ofSeconds(1)) =
        FeeCacheProperties(maximumSize = 1_000, expireAfterWrite = Duration.ofDays(1), lookupTimeout = lookupTimeout)

    @Test
    fun `finds a cached charge without asking Postgres`() =
        runBlocking<Unit> {
            // given
            val records = InMemoryFeeRecordRepository()
            cache.put("txn_001", response)

            // when
            val found = ChargedFees(cache, records, properties()).find("txn_001")

            // then
            assertThat(found).isEqualTo(response)
            assertThat(records.calls).isEmpty()
        }

    @Test
    fun `finds a charge in Postgres on a cache miss and caches it`() =
        runBlocking<Unit> {
            // given
            val records = InMemoryFeeRecordRepository("txn_001" to FeeRecord.inProgress("run").complete(response))
            val chargedFees = ChargedFees(cache, records, properties())

            // when
            val first = chargedFees.find("txn_001")
            val second = chargedFees.find("txn_001")

            // then
            assertThat(listOf(first, second)).containsOnly(response)
            assertThat(records.calls.map { it.name }).containsExactly("findCharged")
            assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
        }

    @Test
    fun `finds nothing for a transaction that has no record`() =
        runBlocking<Unit> {
            // given
            val chargedFees = ChargedFees(cache, InMemoryFeeRecordRepository(), properties())

            // when
            val found = chargedFees.find("txn_001")

            // then
            assertThat(found).isNull()
            assertThat(cache.asMap()).isEmpty()
        }

    @Test
    fun `finds nothing for a transaction claimed but not charged yet, and caches nothing`() =
        runBlocking<Unit> {
            // given
            val chargedFees = ChargedFees(cache, InMemoryFeeRecordRepository("txn_001" to FeeRecord.inProgress("run")), properties())

            // when
            val found = chargedFees.find("txn_001")

            // then
            assertThat(found).isNull()
            assertThat(cache.asMap()).isEmpty()
        }

    @Test
    fun `a failed Postgres read finds nothing, so Restate answers`() =
        runBlocking<Unit> {
            // given
            val records = InMemoryFeeRecordRepository(failing = mapOf("findCharged" to IllegalStateException("database unreachable")))

            // when
            val found = ChargedFees(cache, records, properties()).find("txn_001")

            // then
            assertThat(found).isNull()
        }

    @Test
    @Timeout(10)
    fun `a Postgres read that outlasts the lookup timeout finds nothing, so Restate answers`() =
        runBlocking<Unit> {
            // given: a database that never answers
            val records = InMemoryFeeRecordRepository(hanging = setOf("findCharged"))
            val chargedFees = ChargedFees(cache, records, properties(lookupTimeout = Duration.ofMillis(50)))

            // when
            val found = chargedFees.find("txn_001")

            // then: given up after the timeout instead of waiting for the database, and nothing cached
            assertThat(found).isNull()
            assertThat(records.calls.map { it.name }).containsExactly("findCharged")
            assertThat(cache.asMap()).isEmpty()
        }

    @Test
    fun `remembers a charge under its transaction id`() {
        // given
        val chargedFees = ChargedFees(cache, InMemoryFeeRecordRepository(), properties())

        // when
        chargedFees.remember(response)

        // then
        assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
    }
}
