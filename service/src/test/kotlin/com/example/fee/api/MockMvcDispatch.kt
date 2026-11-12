package com.example.fee.api

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import java.time.Duration

/** How long a test waits for the suspend controller's result; the mocked service answers at once, so it is a ceiling. */
private val ASYNC_RESULT_WAIT: Duration = Duration.ofSeconds(10)

/**
 * Performs [request] the way a client sees it. The controller is a suspend function, so Spring MVC handles a request
 * that reaches it asynchronously; this waits for that result and dispatches it, so the returned actions see the final
 * response. A request rejected before the controller (bad JSON, wrong media type) is returned as it is.
 */
fun MockMvc.performToCompletion(request: RequestBuilder): ResultActions {
    val started = perform(request)
    val result = started.andReturn()
    if (!result.request.isAsyncStarted) return started
    // A bounded wait on the result's own latch: the packaged async timeout is 0 (none), which MockMvc reads as "don't wait"
    result.getAsyncResult(ASYNC_RESULT_WAIT.toMillis())
    return perform(asyncDispatch(result))
}
