package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class CloudflareCooldownTest {
    private val enabled = MutableStateFlow(false)
    private val request = Request.Builder().url("https://example.org/").build()
    private val chain = mockk<Interceptor.Chain>()
    private val interceptor = CloudflareInterceptor(setUserAgent = {})

    @BeforeEach
    fun setup() {
        mockkStatic("suwayomi.tachidesk.server.ServerConfigKt")
        val config = mockk<ServerConfig>()
        every { serverConfig } returns config
        every { config.flareSolverrEnabled } returns enabled
        every { config.flareSolverrAsResponseFallback } returns MutableStateFlow(false)
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .header("cf-mitigated", "challenge")
                .header("Retry-After", "3600")
                .body("Challenge".toResponseBody())
                .build()
        }
        mockkObject(CFClearance)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(CFClearance)
        unmockkStatic("suwayomi.tachidesk.server.ServerConfigKt")
    }

    @Test
    fun `disabled solver still reports a typed challenge with Retry-After`() {
        val error = assertFailsWith<CloudflareChallengeException> { interceptor.intercept(chain) }
        assertEquals(3600.seconds, error.retryAfter)
    }

    @Test
    fun `failed solver retains the original challenge context`() {
        enabled.value = true
        val failure = IOException("Solver timed out")
        coEvery { CFClearance.resolveWithFlareSolver(any(), any()) } throws failure
        val error = assertFailsWith<CloudflareChallengeException> { interceptor.intercept(chain) }
        assertEquals(3600.seconds, error.retryAfter)
        assertSame(failure, error.cause)
    }

    @Test
    fun `a challenge returned after solving is still reported as blocked`() {
        enabled.value = true
        val result = mockk<CFClearance.FlareSolverResponse>()
        every { result.message } returns "Challenge solved"
        every { result.solution.userAgent } returns "Test browser"
        coEvery { CFClearance.resolveWithFlareSolver(any(), any()) } returns result
        every { CFClearance.requestWithFlareSolverr(any(), any(), any()) } returns request
        val error = assertFailsWith<CloudflareChallengeException> { interceptor.intercept(chain) }
        assertEquals(3600.seconds, error.retryAfter)
    }
}
