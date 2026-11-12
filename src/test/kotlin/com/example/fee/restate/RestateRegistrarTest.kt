package com.example.fee.restate

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.fee.restate.RegistrationStatus.Snapshot
import com.example.fee.restate.RegistrationStatus.State
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledFuture

class RestateRegistrarTest {

    private val advertisedUrl = "http://dev:9080"
    private val backoff = Duration.ofSeconds(2)
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val admin = mock(RestateAdminClient::class.java)
    private val status = RegistrationStatus()

    // Retries the registrar hands to the scheduler, run by the test one at a time instead of after a real wait.
    private val scheduled = ArrayDeque<Pair<Runnable, Instant>>()
    private val pendingRetry = mock(ScheduledFuture::class.java)
    private val scheduler = mock(TaskScheduler::class.java).also { scheduler ->
        doAnswer { invocation ->
            scheduled.addLast(invocation.getArgument<Runnable>(0) to invocation.getArgument<Instant>(1))
            pendingRetry
        }.`when`(scheduler).schedule(any(Runnable::class.java), any(Instant::class.java))
    }

    private val logger = LoggerFactory.getLogger(RestateRegistrar::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().also {
        it.start()
        logger.addAppender(it)
    }

    @AfterEach
    fun detachLogs() {
        logger.detachAppender(logs)
    }

    private fun registrar(enabled: Boolean = true, maxAttempts: Int = 3) = RestateRegistrar(
        adminClient = admin,
        properties = RestateProperties(
            ingressUrl = "http://restate:8080",
            adminUrl = "http://restate:9070",
            endpointPort = 9080,
            advertisedUrl = advertisedUrl,
            attachTimeout = Duration.ofSeconds(10),
            registration = RestateProperties.Registration(enabled, maxAttempts, backoff),
        ),
        status = status,
        taskScheduler = scheduler,
        clock = clock,
    )

    private fun runNextRetry(): Instant {
        val (task, at) = scheduled.removeFirst()
        task.run()
        return at
    }

    private val feeWorkflow = ServiceDescription(
        name = "FeeWorkflow",
        type = "Workflow",
        handlers = listOf(
            HandlerDescription("run", "Workflow", "JSON 'FeeWorkflowInput'", "application/json"),
            HandlerDescription("status", "Shared", "none", "JSON string"),
        ),
    )
    private val transactionFeeRecord = ServiceDescription(
        name = "TransactionFeeRecord",
        type = "VirtualObject",
        handlers = listOf(
            HandlerDescription("claim", "Exclusive", "JSON string", "application/json"),
            HandlerDescription("get", "Shared", "none", "application/json"),
        ),
    )
    private val currentServices = listOf(feeWorkflow, transactionFeeRecord)

    // Restate stores the URI with a trailing slash (D31)
    private val ourDeployment = DeploymentSummary("dp_ours", "$advertisedUrl/", listOf(ServiceRevision("FeeWorkflow", 1)))
    private val otherDeployment = DeploymentSummary("dp_other", "http://other:9080/", listOf(ServiceRevision("Other", 1)))

    private fun unreachable() = RestateAdminException("Restate admin API at http://restate:9070 failed to list deployments: Connection refused")

    private fun warnings() = logs.list.filter { it.level == Level.WARN }

    @Test
    fun `registers the endpoint when Restate does not know it`() {
        // given
        `when`(admin.listDeployments()).thenReturn(listOf(otherDeployment))
        `when`(admin.register(advertisedUrl, false, false)).thenReturn(RegistrationResult("dp_new", currentServices))

        // when
        registrar().onApplicationReady()

        // then
        verify(admin).register(advertisedUrl, false, false)
        verify(admin, times(1)).register(anyString(), anyBoolean(), anyBoolean())
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `does nothing when the endpoint is registered with the current services`() {
        // given: the same services, listed in another order
        `when`(admin.listDeployments()).thenReturn(listOf(otherDeployment, ourDeployment))
        `when`(admin.register(advertisedUrl, true, true)).thenReturn(RegistrationResult("dp_ours", currentServices))
        `when`(admin.deployment("dp_ours")).thenReturn(
            DeploymentDetail(
                "dp_ours",
                "$advertisedUrl/",
                listOf(transactionFeeRecord, feeWorkflow.copy(handlers = feeWorkflow.handlers.reversed())),
            ),
        )

        // when
        registrar().onApplicationReady()

        // then: only the dry run, nothing registered
        verify(admin).register(advertisedUrl, true, true)
        verify(admin, times(1)).register(anyString(), anyBoolean(), anyBoolean())
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `re-registers with force when the services changed`() {
        // given: the endpoint now has a handler Restate doesn't know
        val changed = listOf(
            feeWorkflow.copy(handlers = feeWorkflow.handlers + HandlerDescription("cancel", "Shared", "none", "none")),
            transactionFeeRecord,
        )
        `when`(admin.listDeployments()).thenReturn(listOf(ourDeployment))
        `when`(admin.register(advertisedUrl, true, true)).thenReturn(RegistrationResult("dp_ours", changed))
        `when`(admin.deployment("dp_ours")).thenReturn(DeploymentDetail("dp_ours", "$advertisedUrl/", currentServices))
        `when`(admin.register(advertisedUrl, true, false)).thenReturn(RegistrationResult("dp_ours", changed))

        // when
        registrar().onApplicationReady()

        // then
        verify(admin).register(advertisedUrl, true, false)
        verify(admin, never()).register(advertisedUrl, false, false)
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
    }

    @Test
    fun `re-registers with force when a handler's input changed`() {
        // given
        val changedInput = listOf(
            feeWorkflow.copy(
                handlers = listOf(
                    HandlerDescription("run", "Workflow", "JSON 'FeeWorkflowInputV2'", "application/json"),
                    feeWorkflow.handlers[1],
                ),
            ),
            transactionFeeRecord,
        )
        `when`(admin.listDeployments()).thenReturn(listOf(ourDeployment))
        `when`(admin.register(advertisedUrl, true, true)).thenReturn(RegistrationResult("dp_ours", changedInput))
        `when`(admin.deployment("dp_ours")).thenReturn(DeploymentDetail("dp_ours", "$advertisedUrl/", currentServices))
        `when`(admin.register(advertisedUrl, true, false)).thenReturn(RegistrationResult("dp_ours", changedInput))

        // when
        registrar().onApplicationReady()

        // then
        verify(admin).register(advertisedUrl, true, false)
        assertThat(status.state).isEqualTo(State.REGISTERED)
    }

    @Test
    fun `retries with backoff while the admin API is down, then registers`() {
        // given
        `when`(admin.listDeployments()).thenThrow(unreachable(), unreachable()).thenReturn(emptyList())
        `when`(admin.register(advertisedUrl, false, false)).thenReturn(RegistrationResult("dp_new", currentServices))
        val registrar = registrar(maxAttempts = 3)

        // when: the first attempt fails
        registrar.onApplicationReady()

        // then
        assertThat(status.current()).isEqualTo(Snapshot(State.PENDING, unreachable().message))
        assertThat(scheduled).hasSize(1)

        // when: the second fails, the third succeeds
        val secondAt = runNextRetry()
        val thirdAt = runNextRetry()

        // then
        assertThat(secondAt).isEqualTo(now.plus(backoff))
        assertThat(thirdAt).isEqualTo(now.plus(backoff))
        verify(admin, times(3)).listDeployments()
        verify(admin).register(advertisedUrl, false, false)
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
        assertThat(scheduled).isEmpty()
        assertThat(warnings()).isEmpty()
    }

    @Test
    fun `keeps the app running past max-attempts with a WARN, and the background retry later registers`() {
        // given: Restate stays down for 3 attempts
        `when`(admin.listDeployments())
            .thenThrow(unreachable(), unreachable(), unreachable())
            .thenReturn(emptyList())
        `when`(admin.register(advertisedUrl, false, false)).thenReturn(RegistrationResult("dp_new", currentServices))
        val registrar = registrar(maxAttempts = 2)

        // when: startup, then the second attempt
        assertThatCode { registrar.onApplicationReady() }.doesNotThrowAnyException()
        runNextRetry()

        // then: startup was not failed, the status is FAILING and a WARN names the endpoint
        assertThat(status.current()).isEqualTo(Snapshot(State.FAILING, unreachable().message))
        assertThat(warnings()).singleElement().satisfies({ warning ->
            assertThat(warning.formattedMessage)
                .contains(advertisedUrl, "after 2 attempts", "Connection refused", "keeps running", "PT2S")
        })
        assertThat(scheduled).hasSize(1)

        // when: the background retry fails once more, then Restate is back
        runNextRetry()
        val failingAgain = status.current()
        runNextRetry()

        // then: one WARN only, then registered
        assertThat(failingAgain.state).isEqualTo(State.FAILING)
        assertThat(warnings()).hasSize(1)
        verify(admin).register(advertisedUrl, false, false)
        assertThat(status.current()).isEqualTo(Snapshot(State.REGISTERED, null))
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `logs the WARN on the first failure when max-attempts is 1`() {
        // given
        `when`(admin.listDeployments()).thenThrow(unreachable())

        // when
        registrar(maxAttempts = 1).onApplicationReady()

        // then
        assertThat(status.state).isEqualTo(State.FAILING)
        assertThat(warnings()).hasSize(1)
        assertThat(scheduled).hasSize(1)
    }

    @Test
    fun `retries an unexpected error like an unreachable admin API`() {
        // given: Restate answers, but discovering our endpoint fails
        `when`(admin.listDeployments()).thenReturn(listOf(ourDeployment))
        `when`(admin.register(advertisedUrl, true, true)).thenThrow(IllegalStateException("endpoint not reachable"))

        // when
        registrar().onApplicationReady()

        // then
        assertThat(status.current()).isEqualTo(Snapshot(State.PENDING, "endpoint not reachable"))
        assertThat(scheduled).hasSize(1)
    }

    @Test
    fun `skips registration when it is disabled`() {
        // given
        val registrar = registrar(enabled = false)

        // when
        registrar.onApplicationReady()

        // then
        verifyNoInteractions(admin, scheduler)
        assertThat(status.current()).isEqualTo(Snapshot(State.PENDING, null))
    }

    @Test
    fun `destroy cancels the pending retry and stops further attempts`() {
        // given
        `when`(admin.listDeployments()).thenThrow(unreachable())
        val registrar = registrar()
        registrar.onApplicationReady()

        // when
        registrar.destroy()
        runNextRetry()

        // then
        verify(pendingRetry).cancel(false)
        verify(admin, times(1)).listDeployments()
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `a scheduler that is shutting down does not make the attempt throw`() {
        // given
        `when`(admin.listDeployments()).thenThrow(unreachable())
        val shuttingDown = mock(TaskScheduler::class.java)
        `when`(shuttingDown.schedule(any(Runnable::class.java), any(Instant::class.java)))
            .thenThrow(TaskRejectedException("scheduler shut down"))
        val registrar = RestateRegistrar(
            adminClient = admin,
            properties = RestateProperties(
                "http://restate:8080", "http://restate:9070", 9080, advertisedUrl, Duration.ofSeconds(10),
                RestateProperties.Registration(true, 3, backoff),
            ),
            status = status,
            taskScheduler = shuttingDown,
            clock = clock,
        )

        // when / then
        assertThatCode { registrar.onApplicationReady() }.doesNotThrowAnyException()
        assertThat(status.state).isEqualTo(State.PENDING)
    }
}
