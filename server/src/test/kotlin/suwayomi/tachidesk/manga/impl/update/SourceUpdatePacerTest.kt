package suwayomi.tachidesk.manga.impl.update

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import suwayomi.tachidesk.graphql.types.SourceUpdatePolicy
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SourceUpdatePacerTest {
    private val fixedPolicy = SourceUpdatePolicy("1", minDelaySeconds = 60, maxDelaySeconds = 60)

    @Test
    fun `pause starts after a fetch finishes and idle time counts towards it`() =
        runTest {
            val pacer = SourceUpdatePacer(Semaphore(1), { fixedPolicy }, testScheduler.timeSource)
            pacer.run {
                assertEquals(0L, testScheduler.currentTime)
                delay(20.seconds)
            }
            advanceTimeBy(30.seconds)
            pacer.run { assertEquals(80_000L, testScheduler.currentTime) }

            advanceTimeBy(120.seconds)
            pacer.run { assertEquals(200_000L, testScheduler.currentTime) }
        }

    @Test
    fun `random pauses stay within configured bounds`() =
        runTest {
            val pacer = SourceUpdatePacer(Semaphore(1), { SourceUpdatePolicy("1") }, testScheduler.timeSource, Random(42))
            val starts = mutableListOf<Long>()
            repeat(30) { pacer.run { starts.add(testScheduler.currentTime) } }
            val gaps = starts.zipWithNext { first, second -> second - first }
            assertEquals(0L, starts.first())
            assertTrue(gaps.all { it in 60_000L..300_000L })
            assertTrue(gaps.distinct().size > 1)
        }

    @Test
    fun `a source in cooldown does not hold a parallel update slot`() =
        runTest {
            val semaphore = Semaphore(1)
            val blocked = SourceUpdatePacer(semaphore, { fixedPolicy }, testScheduler.timeSource)
            val healthy = SourceUpdatePacer(semaphore, { fixedPolicy.copy(sourceId = "2") }, testScheduler.timeSource)
            assertFailsWith<IOException> { blocked.run { throw IOException(CloudflareChallengeException()) } }

            var blockedRan = false
            val waiting = launch { blocked.run { blockedRan = true } }
            runCurrent()
            healthy.run { assertEquals(0L, testScheduler.currentTime) }
            assertFalse(blockedRan)
            waiting.join()
            assertTrue(blockedRan)
            assertEquals(1_800_000L, testScheduler.currentTime)
        }

    @Test
    fun `cancelling a wait keeps its deadline and releases the source lock`() =
        runTest {
            val semaphore = Semaphore(1)
            val pacer = SourceUpdatePacer(semaphore, { fixedPolicy }, testScheduler.timeSource)
            pacer.run {}
            var cancelledRan = false
            val waiting = launch { pacer.run { cancelledRan = true } }
            advanceTimeBy(10.seconds)
            waiting.cancelAndJoin()
            assertEquals(1, semaphore.availablePermits)
            pacer.run { assertEquals(60_000L, testScheduler.currentTime) }
            assertFalse(cancelledRan)
        }

    @Test
    fun `cancelling an active fetch still spaces out the next attempt`() =
        runTest {
            val semaphore = Semaphore(1)
            val pacer = SourceUpdatePacer(semaphore, { fixedPolicy }, testScheduler.timeSource)
            val fetching = launch { pacer.run { delay(120.seconds) } }
            advanceTimeBy(10.seconds)
            fetching.cancelAndJoin()
            assertEquals(1, semaphore.availablePermits)
            pacer.run { assertEquals(70_000L, testScheduler.currentTime) }
        }

    @Test
    fun `concurrent jobs for the same source remain serialized`() =
        runTest {
            val pacer = SourceUpdatePacer(Semaphore(2), { fixedPolicy }, testScheduler.timeSource)
            val starts = mutableListOf<Long>()
            val jobs =
                List(3) {
                    launch {
                        pacer.run {
                            starts.add(testScheduler.currentTime)
                            delay(10.seconds)
                        }
                    }
                }
            jobs.forEach { it.join() }
            assertEquals(listOf(0L, 70_000L, 140_000L), starts)
        }

    @Test
    fun `repeated protection failures back off to the cap and success resets escalation`() =
        runTest {
            val policy = fixedPolicy.copy(minDelaySeconds = 0, maxDelaySeconds = 0, maxCooldownSeconds = 7200)
            val pacer = SourceUpdatePacer(Semaphore(1), { policy }, testScheduler.timeSource)
            val starts = mutableListOf<Long>()
            repeat(4) {
                assertFailsWith<HttpException> {
                    pacer.run {
                        starts.add(testScheduler.currentTime / 1000)
                        throw HttpException(429)
                    }
                }
            }
            assertEquals(listOf(0L, 1800L, 5400L, 12600L), starts)
            pacer.run { assertEquals(19800_000L, testScheduler.currentTime) }
            assertFailsWith<HttpException> { pacer.run { throw HttpException(429) } }
            pacer.run { assertEquals(21600_000L, testScheduler.currentTime) }
        }

    @Test
    fun `Retry-After is honored even when longer than the configured cooldown cap`() =
        runTest {
            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("https://example.org/").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "90000")
                    .body("".toResponseBody())
                    .build()
            val pacer = SourceUpdatePacer(Semaphore(1), { fixedPolicy }, testScheduler.timeSource)
            response.use { assertFailsWith<HttpException> { pacer.run { throw HttpException(it) } } }
            pacer.run { assertEquals(90_000_000L, testScheduler.currentTime) }
        }

    @Test
    fun `ordinary failures get a normal pause rather than a protection cooldown`() =
        runTest {
            val pacer = SourceUpdatePacer(Semaphore(1), { fixedPolicy }, testScheduler.timeSource)
            assertFailsWith<HttpException> { pacer.run { throw HttpException(403) } }
            pacer.run { assertEquals(60_000L, testScheduler.currentTime) }
            assertFailsWith<IllegalArgumentException> { pacer.run { throw IllegalArgumentException("Parse failure") } }
            pacer.run { assertEquals(180_000L, testScheduler.currentTime) }
        }

    @Test
    fun `unconfigured sources run immediately and removing a policy clears its cooldown`() =
        runTest {
            var policy: SourceUpdatePolicy? = null
            val pacer = SourceUpdatePacer(Semaphore(1), { policy }, testScheduler.timeSource)
            repeat(2) { assertFailsWith<HttpException> { pacer.run { throw HttpException(429) } } }
            pacer.run { assertEquals(0L, testScheduler.currentTime) }

            policy = fixedPolicy
            assertFailsWith<HttpException> { pacer.run { throw HttpException(429) } }
            policy = null
            pacer.run { assertEquals(0L, testScheduler.currentTime) }
        }
}
