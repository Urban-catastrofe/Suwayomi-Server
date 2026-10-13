package eu.kanade.tachiyomi.network

import okhttp3.Response
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @see Response.isSuccessful
 * @since tachiyomix 1.6
 * @param code [Int] the HTTP status code
 */
class HttpException(
    val code: Int,
) : IllegalStateException("HTTP error $code") {
    var retryAfter: Duration? = null
        private set

    var isCloudflareChallenge: Boolean = false
        private set

    // Keep the single-argument constructor for binary compatibility with extensions.
    constructor(response: Response) : this(response.code) {
        retryAfter = response.retryAfterDelay()
        isCloudflareChallenge = response.isCloudflareChallenge()
    }
}

internal fun Response.retryAfterDelay(now: Instant = Instant.now()): Duration? {
    val value = header("Retry-After")?.trim() ?: return null
    value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 }?.seconds }

    return runCatching {
        val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        java.time.Duration
            .between(now, retryAt)
            .toKotlinDuration()
            .coerceAtLeast(Duration.ZERO)
    }.getOrNull()
}

internal fun Response.isCloudflareChallenge(): Boolean =
    header("cf-mitigated").equals("challenge", ignoreCase = true) ||
        (code in listOf(403, 503) && header("Server")?.lowercase() in listOf("cloudflare", "cloudflare-nginx"))
