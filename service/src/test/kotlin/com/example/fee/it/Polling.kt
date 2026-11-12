package com.example.fee.it

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Bounded polling: checks [condition] every [interval] until it holds, and fails naming [description] after [timeout].
 * For state the test can only observe from outside (the admin API, a stored record, a log line), never a fixed wait.
 */
fun awaitUntil(
    description: String,
    timeout: Duration = Duration.ofSeconds(60),
    interval: Duration = Duration.ofMillis(200),
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        if (System.nanoTime() - deadline > 0) {
            throw AssertionError("Timed out after $timeout waiting until $description")
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(interval.toMillis()))
    }
}
