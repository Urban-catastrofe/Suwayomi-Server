package suwayomi.tachidesk.manga.impl.update

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import suwayomi.tachidesk.graphql.types.SourceUpdatePolicy
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class SourceUpdatePacer(
    private val semaphore: Semaphore,
    private val policy: () -> SourceUpdatePolicy?,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private var nextUpdateAt: TimeMark? = null
    private var previousCooldown = Duration.ZERO

    suspend fun run(update: suspend () -> Unit) {
        mutex.withLock {
            val settings = policy()
            if (settings == null) {
                nextUpdateAt = null
                previousCooldown = Duration.ZERO
            }

            // Idle time counts towards the delay; waiting never occupies a shared update slot.
            nextUpdateAt?.let { delay(-it.elapsedNow()) }

            semaphore.withPermit {
                var cooldown = Duration.ZERO
                try {
                    update()
                    previousCooldown = Duration.ZERO
                } catch (e: Exception) {
                    if (e !is CancellationException && settings != null) {
                        cooldownHint(e)?.let { retryAfter ->
                            previousCooldown =
                                (previousCooldown * 2).coerceIn(
                                    settings.initialCooldownSeconds.seconds,
                                    settings.maxCooldownSeconds.seconds,
                                )
                            // A server-requested wait is a minimum, even if it exceeds our backoff cap.
                            cooldown = maxOf(previousCooldown, retryAfter)
                            logger.warn {
                                "Pausing library updates for source ${settings.sourceId} for $cooldown " +
                                    "after a rate limit or unresolved Cloudflare challenge"
                            }
                        }
                    }
                    throw e
                } finally {
                    val pause =
                        settings?.let {
                            random.nextLong(it.minDelaySeconds.toLong(), it.maxDelaySeconds.toLong() + 1).seconds
                        } ?: Duration.ZERO
                    nextUpdateAt = timeSource.markNow() + maxOf(pause, cooldown)
                }
            }
        }
    }

    private fun cooldownHint(error: Throwable): Duration? =
        generateSequence(error) { it.cause }
            .take(32)
            .mapNotNull {
                when {
                    it is CloudflareChallengeException -> it.retryAfter ?: Duration.ZERO
                    it is HttpException && (it.code == 429 || it.isCloudflareChallenge) -> it.retryAfter ?: Duration.ZERO
                    else -> null
                }
            }.maxOrNull()
}
