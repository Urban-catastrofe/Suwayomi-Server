package eu.kanade.tachiyomi.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HttpExceptionTest {
    private fun response(
        code: Int = 429,
        retryAfter: String? = null,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://example.org/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test response")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body("".toResponseBody())
            .build()

    @Test
    fun `Retry-After accepts seconds and HTTP dates and ignores invalid values`() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        response(retryAfter = "120").use { assertEquals(120.seconds, it.retryAfterDelay(now)) }
        response(retryAfter = "Tue, 13 Oct 2026 12:30:00 GMT").use { assertEquals(30.minutes, it.retryAfterDelay(now)) }
        response(retryAfter = "Tue, 13 Oct 2026 11:30:00 GMT").use { assertEquals(Duration.ZERO, it.retryAfterDelay(now)) }
        listOf(null, "", "garbage", "-1").forEach { value ->
            response(retryAfter = value).use { assertNull(it.retryAfterDelay(now)) }
        }
    }

    @Test
    fun `Cloudflare responses are distinguished from unrelated forbidden responses`() {
        response(403).use { assertFalse(HttpException(it).isCloudflareChallenge) }
        response(403).newBuilder().header("Server", "cloudflare").build().use {
            assertTrue(HttpException(it).isCloudflareChallenge)
        }
        response(503).newBuilder().header("cf-mitigated", "challenge").build().use {
            assertTrue(HttpException(it).isCloudflareChallenge)
        }
        response(200).newBuilder().header("Server", "cloudflare").build().use {
            assertFalse(it.isCloudflareChallenge())
        }
    }

    @Test
    fun `suspend requests retain rate limit metadata`() =
        runTest {
            val client = OkHttpClient.Builder().addInterceptor { response(retryAfter = "3600") }.build()
            try {
                val error =
                    assertFailsWith<HttpException> {
                        client.newCall(Request.Builder().url("https://example.org/").build()).awaitSuccess()
                    }
                assertEquals(429, error.code)
                assertEquals(3600.seconds, error.retryAfter)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy observable requests retain rate limit metadata`() {
        val client = OkHttpClient.Builder().addInterceptor { response(retryAfter = "3600") }.build()
        try {
            val error =
                assertFailsWith<HttpException> {
                    client
                        .newCall(Request.Builder().url("https://example.org/").build())
                        .asObservableSuccess()
                        .toBlocking()
                        .single()
                }
            assertEquals(429, error.code)
            assertEquals(3600.seconds, error.retryAfter)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
