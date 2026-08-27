package dev.avinya.ads

import dev.avinya.ads.internal.dispatchAfterInitializeHooks
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Records the phases it saw, and can fail on demand. */
private class RecordingHook(
    private val failWith: Throwable? = null,
) : AdInitializationHook {
    val phases = mutableListOf<AdInitializationPhase>()
    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
        phases += phase
        failWith?.let { throw it }
    }
}

private fun configWith(vararg hooks: AdInitializationHook): AdConfig = AdConfig(
    appIds = AdAppIds(
        android = "ca-app-pub-1111111111111111~1111111111",
        ios = "ca-app-pub-1111111111111111~2222222222",
    ),
    initializationHooks = hooks.toList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class PostInitializationHooksTest {

    // --- the raw dispatch contract, previously untested -------------------------------------

    @Test
    fun `dispatch invokes every hook once with the requested phase`() = runTest {
        val first = RecordingHook()
        val second = RecordingHook()

        configWith(first, second).dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)

        assertContentEquals(listOf(AdInitializationPhase.BeforeConsentRequest), first.phases)
        assertContentEquals(listOf(AdInitializationPhase.BeforeConsentRequest), second.phases)
    }

    @Test
    fun `dispatch propagates a throwing hook to its caller`() = runTest {
        // The raw dispatch is deliberately unguarded -- BeforeMobileAdsInitialize failures SHOULD
        // abort initialization. Only the After phase is isolated, by dispatchAfterInitializeHooks.
        assertFailsWith<IllegalStateException> {
            configWith(RecordingHook(failWith = IllegalStateException("hook exploded")))
                .dispatchInitializationHooks(AdInitializationPhase.BeforeMobileAdsInitialize)
        }
    }

    @Test
    fun `dispatch stops at the first throwing hook`() = runTest {
        val throwing = RecordingHook(failWith = IllegalStateException("boom"))
        val later = RecordingHook()

        assertFailsWith<IllegalStateException> {
            configWith(throwing, later)
                .dispatchInitializationHooks(AdInitializationPhase.BeforeMobileAdsInitialize)
        }
        assertTrue(later.phases.isEmpty(), "a failed pre-init hook must abort the sequence")
    }

    // --- the INIT-01 isolation guarantee ----------------------------------------------------

    @Test
    fun `a throwing after-initialize hook does not surface as an initialization failure`() = runTest {
        val hook = RecordingHook(failWith = IllegalStateException("publisher hook exploded"))

        // Must not throw. This is the whole point: by the time these hooks run, the native ad SDK
        // singleton is initialized and its identity committed. Letting a host hook's failure
        // propagate is what used to leave appliedConfigIdentity null while GMA was already
        // running -- after which a retry with a different app ID would try to reconfigure an
        // immutable singleton.
        dispatchAfterInitializeHooks(configWith(hook))

        assertContentEquals(listOf(AdInitializationPhase.AfterMobileAdsInitialize), hook.phases)
    }

    @Test
    fun `a hook throwing CancellationException while the scope is active is contained`() = runTest {
        val hook = RecordingHook(failWith = CancellationException("hook cancelled itself"))

        // A hook's own CancellationException is a hook failure, not a teardown of the detached
        // initialization scope, so it must be contained like any other.
        dispatchAfterInitializeHooks(configWith(hook))

        assertEquals(1, hook.phases.size)
    }

    @Test
    fun `tearing down the enclosing scope still cancels`() = runTest {
        val started = CompletableDeferred<Unit>()
        var observed: Throwable? = null
        val hook = object : AdInitializationHook {
            override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch {
            try {
                dispatchAfterInitializeHooks(configWith(hook))
            } catch (t: Throwable) {
                observed = t
                throw t
            }
        }
        started.await()
        job.cancelAndJoin()
        advanceUntilIdle()

        assertTrue(
            observed is CancellationException,
            "real scope teardown must not be swallowed as a hook failure"
        )
    }

    @Test
    fun `later after-initialize hooks are skipped once one throws`() = runTest {
        val throwing = RecordingHook(failWith = IllegalStateException("boom"))
        val later = RecordingHook()

        dispatchAfterInitializeHooks(configWith(throwing, later))

        // Known, accepted consequence of reusing the shared dispatch: isolation is at the phase
        // boundary, not per hook. Documented here so a change in either direction is deliberate.
        assertTrue(later.phases.isEmpty())
    }
}
