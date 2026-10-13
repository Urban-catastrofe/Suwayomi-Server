package eu.kanade.tachiyomi.network

import java.io.IOException
import kotlin.time.Duration

internal class CloudflareChallengeException(
    message: String = "Cloudflare challenge could not be resolved",
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : IOException(cause?.message?.let { "$message: $it" } ?: message, cause)
