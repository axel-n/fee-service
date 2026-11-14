package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecordRepository
import com.github.benmanes.caffeine.cache.Cache
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.util.concurrent.TimeoutException
import kotlin.time.toKotlinDuration

/**
 * The charged fees `FeeService` answers without Restate: first the local [cache], then Postgres. Only charged
 * responses are found and kept. They never change, so an entry can't go stale on any instance, and the cache's size and
 * expiry only bound its memory. Ids that are not charged yet are left to `FeeService`, which submits their workflow.
 */
@Component
class ChargedFees(
    private val cache: Cache<String, FeeResponse>,
    private val feeRecords: FeeRecordRepository,
    properties: FeeCacheProperties,
) {
    private val lookupTimeout = properties.lookupTimeout.toKotlinDuration()

    /**
     * The charged response of [transactionId], or `null` when it isn't charged. Postgres is asked only on a cache miss,
     * and its answer is cached. A Postgres read that fails throws, and one that takes longer than
     * `fee-cache.lookup-timeout` (a database that hangs) throws a [TimeoutException]: without the answer, `FeeService`
     * can't tell whether the id is charged already.
     */
    suspend fun find(transactionId: String): FeeResponse? = cache.getIfPresent(transactionId) ?: charged(transactionId)?.also(::remember)

    /** Keeps [response], the charged fee of its transaction, for the next request of the same `transaction_id`. */
    fun remember(response: FeeResponse) = cache.put(response.transactionId, response)

    // A plain exception, not withTimeout's TimeoutCancellationException: that one is a CancellationException, which a
    // caller passes through as its own cancellation instead of handling it as a failure.
    private suspend fun charged(transactionId: String): FeeResponse? = try {
        withTimeout(lookupTimeout) { feeRecords.findCharged(transactionId) }
    } catch (_: TimeoutCancellationException) {
        throw TimeoutException("Reading the fee record of $transactionId took longer than $lookupTimeout")
    }
}
