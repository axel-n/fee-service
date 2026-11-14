package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecordRepository
import com.github.benmanes.caffeine.cache.Cache
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = LoggerFactory.getLogger(ChargedFees::class.java)

/**
 * The charged fees `FeeService` answers without Restate: first the local [cache], then Postgres. Only charged
 * responses are found and kept. They never change, so an entry can't go stale on any instance, and the cache's size and
 * expiry only bound its memory. Rejections, conflicts and runs still in progress are left to Restate.
 */
@Component
class ChargedFees(
    private val cache: Cache<String, FeeResponse>,
    private val feeRecords: FeeRecordRepository,
    properties: FeeCacheProperties,
) {
    private val lookupTimeout = properties.lookupTimeout

    /**
     * The charged response of [transactionId], or `null` when it isn't known to be charged. Postgres is asked only on a
     * cache miss, and its answer is cached. A Postgres read that fails, or that takes longer than `fee-cache.lookup-timeout`
     * (a database that hangs), is `null` too: Restate answers every id correctly, so the lookup only saves a call to it.
     */
    suspend fun find(transactionId: String): FeeResponse? = cache.getIfPresent(transactionId) ?: charged(transactionId)?.also(::remember)

    /** Keeps [response], the charged fee of its transaction, for the next request of the same `transaction_id`. */
    fun remember(response: FeeResponse) = cache.put(response.transactionId, response)

    private suspend fun charged(transactionId: String): FeeResponse? =
        try {
            withTimeout(lookupTimeout.toKotlinDuration()) { feeRecords.findCharged(transactionId) }
        } catch (_: TimeoutCancellationException) {
            log.warn("Reading the fee record of {} took longer than {}, asking Restate", transactionId, lookupTimeout)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Reading the fee record of {} failed, asking Restate: {}", transactionId, e.toString())
            null
        }
}
