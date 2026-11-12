@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3

package com.example.fee.record

import com.example.fee.record.FeeRecords.HASH
import com.example.fee.record.FeeRecords.OTHER_HASH
import com.example.fee.record.FeeRecords.response
import com.example.fee.record.TransactionFeeRecord.Companion.RECORD
import dev.restate.sdk.ObjectContext
import dev.restate.sdk.endpoint.definition.HandlerType
import dev.restate.sdk.endpoint.definition.ServiceType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.util.Optional

/**
 * The handlers against an in-memory object state. The claim rules themselves are [ClaimDeciderTest]'s; the handlers
 * against a real Restate are covered by the ITs (section 7).
 */
class TransactionFeeRecordTest {

    private val handlers = TransactionFeeRecord(ClaimDecider())

    @Test
    fun `a first claim is new and stores an in-progress record for the payload`() {
        // given
        val state = ObjectState(record = null)

        // when
        val result = handlers.claim(state.ctx, HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.New)
        assertThat(state.record).isEqualTo(FeeRecord.inProgress(HASH))
    }

    @Test
    fun `a claim while another payload holds the id is in progress and leaves the record unchanged`() {
        // given
        val state = ObjectState(record = FeeRecord.inProgress(HASH))

        // when
        val result = handlers.claim(state.ctx, OTHER_HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.InProgress)
        assertThat(state.record).isEqualTo(FeeRecord.inProgress(HASH))
        assertThat(state.writes).isZero()
    }

    @Test
    fun `a claim by the charged payload returns the stored response and leaves the record unchanged`() {
        // given
        val completed = FeeRecord.inProgress(HASH).complete(response)
        val state = ObjectState(record = completed)

        // when
        val result = handlers.claim(state.ctx, HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.AlreadyCompleted(response))
        assertThat(state.record).isEqualTo(completed)
        assertThat(state.writes).isZero()
    }

    @Test
    fun `a claim by another payload after a charge is a conflict and leaves the record unchanged`() {
        // given
        val completed = FeeRecord.inProgress(HASH).complete(response)
        val state = ObjectState(record = completed)

        // when
        val result = handlers.claim(state.ctx, OTHER_HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.Conflict)
        assertThat(state.record).isEqualTo(completed)
        assertThat(state.writes).isZero()
    }

    @Test
    fun `a claim after a failed charge is new and stores an in-progress record for the new payload`() {
        // given
        val state = ObjectState(record = FeeRecord.inProgress(HASH).fail("Charge declined: insufficient funds"))

        // when
        val result = handlers.claim(state.ctx, OTHER_HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.New)
        assertThat(state.record).isEqualTo(FeeRecord.inProgress(OTHER_HASH))
    }

    @Test
    fun `complete stores the response as a completed record, keeping the claiming payload's hash`() {
        // given
        val state = ObjectState(record = FeeRecord.inProgress(HASH))

        // when
        handlers.complete(state.ctx, response)

        // then
        assertThat(state.record).isEqualTo(FeeRecord(FeeRecordStatus.COMPLETED, HASH, response = response))
    }

    @Test
    fun `fail stores the reason as a failed record, keeping the claiming payload's hash`() {
        // given
        val state = ObjectState(record = FeeRecord.inProgress(HASH))

        // when
        handlers.fail(state.ctx, "Charge declined: insufficient funds")

        // then
        assertThat(state.record).isEqualTo(
            FeeRecord(FeeRecordStatus.FAILED, HASH, failureReason = "Charge declined: insufficient funds"),
        )
    }

    @Test
    fun `complete without a claim is a bug that names the transaction and stores nothing`() {
        // given
        val state = ObjectState(record = null)

        // when / then
        assertThatThrownBy { handlers.complete(state.ctx, response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("complete on transaction 'txn_001' without a claim")
        assertThat(state.writes).isZero()
    }

    @Test
    fun `fail without a claim is a bug that names the transaction and stores nothing`() {
        // given
        val state = ObjectState(record = null)

        // when / then
        assertThatThrownBy { handlers.fail(state.ctx, "Charge declined") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("fail on transaction 'txn_001' without a claim")
        assertThat(state.writes).isZero()
    }

    @Test
    fun `complete on a completed record is a bug and keeps the stored response`() {
        // given
        val completed = FeeRecord.inProgress(HASH).complete(response)
        val state = ObjectState(record = completed)

        // when / then
        assertThatThrownBy { handlers.complete(state.ctx, response.copy(fee = response.fee.add(response.fee))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("complete on transaction 'txn_001' whose record is COMPLETED, not IN_PROGRESS")
        assertThat(state.record).isEqualTo(completed)
    }

    @Test
    fun `fail on a completed record is a bug and keeps the record completed`() {
        // given
        val completed = FeeRecord.inProgress(HASH).complete(response)
        val state = ObjectState(record = completed)

        // when / then
        assertThatThrownBy { handlers.fail(state.ctx, "Charge declined") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("fail on transaction 'txn_001' whose record is COMPLETED, not IN_PROGRESS")
        assertThat(state.record).isEqualTo(completed)
    }

    @Test
    fun `complete on a failed record is a bug, because the id has to be claimed again first`() {
        // given
        val failed = FeeRecord.inProgress(HASH).fail("Charge declined")
        val state = ObjectState(record = failed)

        // when / then
        assertThatThrownBy { handlers.complete(state.ctx, response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("complete on transaction 'txn_001' whose record is FAILED, not IN_PROGRESS")
        assertThat(state.record).isEqualTo(failed)
    }

    @Test
    fun `get returns the stored record`() {
        // given
        val completed = FeeRecord.inProgress(HASH).complete(response)
        val state = ObjectState(record = completed)

        // when
        val record = handlers.get(state.ctx)

        // then
        assertThat(record).isEqualTo(completed)
    }

    @Test
    fun `get returns null for an id that was never claimed`() {
        // given
        val state = ObjectState(record = null)

        // when
        val record = handlers.get(state.ctx)

        // then
        assertThat(record).isNull()
    }

    @Test
    fun `is bound as a virtual object with exclusive writes and a shared get`() {
        // given
        val factory = TransactionFeeRecordServiceDefinitionFactory()

        // when
        val definition = factory.create(handlers, null)

        // then
        assertThat(factory.supports(handlers)).isTrue()
        assertThat(definition.serviceName).isEqualTo("TransactionFeeRecord")
        assertThat(definition.serviceType).isEqualTo(ServiceType.VIRTUAL_OBJECT)
        assertThat(definition.handlers.associate { it.name to it.handlerType }).isEqualTo(
            mapOf(
                "claim" to HandlerType.EXCLUSIVE,
                "complete" to HandlerType.EXCLUSIVE,
                "fail" to HandlerType.EXCLUSIVE,
                "get" to HandlerType.SHARED,
            ),
        )
    }

    @Test
    fun `stores the record under the state key 'record'`() {
        // given
        val key = RECORD

        // when
        val name = key.name()

        // then
        assertThat(name).isEqualTo("record")
    }

    /** The object state of transaction `txn_001` in memory, behind a mocked [ObjectContext]. */
    private class ObjectState(var record: FeeRecord?) {
        var writes = 0
            private set

        val ctx: ObjectContext = mock(ObjectContext::class.java).also { ctx ->
            `when`(ctx.key()).thenReturn("txn_001")
            `when`(ctx.get(RECORD)).thenAnswer { Optional.ofNullable(record) }
            doAnswer { invocation ->
                check(invocation.getArgument<Any>(0) == RECORD) { "unexpected state key ${invocation.getArgument<Any>(0)}" }
                record = invocation.getArgument(1)
                writes++
                null
            }.`when`(ctx).set(any(), any<FeeRecord>())
        }
    }
}
