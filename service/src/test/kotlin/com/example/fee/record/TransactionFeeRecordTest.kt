package com.example.fee.record

import com.example.fee.record.FeeRecords.response
import dev.restate.sdk.endpoint.definition.HandlerType
import dev.restate.sdk.endpoint.definition.ServiceDefinitionFactories
import dev.restate.sdk.endpoint.definition.ServiceType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The handlers against an in-memory [FeeRecordState]. The claim rules themselves are [ClaimDeciderTest]'s; the handlers
 * against a real Restate are covered by the ITs.
 */
class TransactionFeeRecordTest {
    @Test
    fun `a first claim is new and stores an in-progress record`() =
        runBlocking<Unit> {
            // given
            val state = InMemoryState(record = null)

            // when
            val result = handlers(state).claim()

            // then
            assertThat(result).isEqualTo(ClaimResult.New)
            assertThat(state.record).isEqualTo(FeeRecord.inProgress())
        }

    @Test
    fun `a claim while an earlier run holds the id is in progress and leaves the record unchanged`() =
        runBlocking<Unit> {
            // given
            val state = InMemoryState(record = FeeRecord.inProgress())

            // when
            val result = handlers(state).claim()

            // then
            assertThat(result).isEqualTo(ClaimResult.InProgress)
            assertThat(state.record).isEqualTo(FeeRecord.inProgress())
            assertThat(state.writes).isZero()
        }

    @Test
    fun `a claim on a charged id returns the stored response and leaves the record unchanged`() =
        runBlocking<Unit> {
            // given
            val completed = FeeRecord.inProgress().complete(response)
            val state = InMemoryState(record = completed)

            // when
            val result = handlers(state).claim()

            // then
            assertThat(result).isEqualTo(ClaimResult.AlreadyCompleted(response))
            assertThat(state.record).isEqualTo(completed)
            assertThat(state.writes).isZero()
        }

    @Test
    fun `complete stores the response as a completed record`() =
        runBlocking<Unit> {
            // given
            val state = InMemoryState(record = FeeRecord.inProgress())

            // when
            handlers(state).complete(response)

            // then
            assertThat(state.record).isEqualTo(FeeRecord(FeeRecordStatus.COMPLETED, response = response))
        }

    @Test
    fun `complete without a claim is a bug that names the transaction and stores nothing`() {
        // given
        val state = InMemoryState(record = null)

        // when / then
        assertThatThrownBy { runBlocking { handlers(state).complete(response) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("complete on transaction 'txn_001' without a claim")
        assertThat(state.writes).isZero()
    }

    @Test
    fun `complete on a completed record is a bug and keeps the stored response`() {
        // given
        val completed = FeeRecord.inProgress().complete(response)
        val state = InMemoryState(record = completed)

        // when / then
        assertThatThrownBy { runBlocking { handlers(state).complete(response.copy(fee = response.fee.add(response.fee))) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("complete on transaction 'txn_001' whose record is COMPLETED, not IN_PROGRESS")
        assertThat(state.record).isEqualTo(completed)
    }

    @Test
    fun `get returns the stored record`() =
        runBlocking<Unit> {
            // given
            val completed = FeeRecord.inProgress().complete(response)
            val state = InMemoryState(record = completed)

            // when
            val record = handlers(state).get()

            // then
            assertThat(record).isEqualTo(completed)
        }

    @Test
    fun `get returns null for an id that was never claimed`() =
        runBlocking<Unit> {
            // given
            val state = InMemoryState(record = null)

            // when
            val record = handlers(state).get()

            // then
            assertThat(record).isNull()
        }

    @Test
    fun `is bound as a virtual object with exclusive writes and a shared get`() {
        // given
        val handlers = handlers(InMemoryState(record = null))

        // when
        val definition = ServiceDefinitionFactories.discover(handlers).create(handlers, null)

        // then
        assertThat(definition.serviceName).isEqualTo("TransactionFeeRecord")
        assertThat(definition.serviceType).isEqualTo(ServiceType.VIRTUAL_OBJECT)
        assertThat(definition.handlers.associate { it.name to it.handlerType }).isEqualTo(
            mapOf(
                "claim" to HandlerType.EXCLUSIVE,
                "complete" to HandlerType.EXCLUSIVE,
                "get" to HandlerType.SHARED,
            ),
        )
    }

    @Test
    fun `stores the record under the state key 'record'`() {
        // given
        val key = RestateFeeRecordState.RECORD

        // when
        val name = key.name()

        // then
        assertThat(name).isEqualTo("record")
    }

    private fun handlers(state: FeeRecordState) = TransactionFeeRecord(ClaimDecider(), state)

    /** The state of transaction `txn_001` in memory. */
    private class InMemoryState(
        var record: FeeRecord?,
    ) : FeeRecordState {
        var writes = 0
            private set

        override suspend fun key(): String = "txn_001"

        override suspend fun get(): FeeRecord? = record

        override suspend fun set(record: FeeRecord) {
            this.record = record
            writes++
        }
    }
}
