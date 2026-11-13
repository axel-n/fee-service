package com.example.fee.it

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * The `Clock` of the shared IT app (it replaces the app's `Clock.systemUTC()`, see [IntegrationTestBeans]). Unpinned, it
 * is the system UTC clock, so other ITs are unaffected; a test can [pin] it to an instant, move it while a workflow
 * runs, and must [reset] it afterwards.
 */
class TestClock : Clock() {
    private val pinned = AtomicReference<Instant?>(null)

    /** From now on the clock reads [instant]. */
    fun pin(instant: Instant) {
        pinned.set(instant)
    }

    /** Back to the system UTC clock. */
    fun reset() {
        pinned.set(null)
    }

    override fun instant(): Instant = pinned.get() ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    // A zone change would only shift the reading of instant(), which the validator does in UTC itself
    override fun withZone(zone: ZoneId): Clock = this
}
