package dev.avinya.ads

import dev.avinya.ads.internal.AppliedConfigurationDecision
import dev.avinya.ads.internal.NativeHandoffDecision
import dev.avinya.ads.internal.appliedConfigurationDecision
import dev.avinya.ads.internal.nativeHandoffDecision
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The observable semantics of a repeat `initialize()`.
 *
 * A conflicting configuration used to log a warning and return the previously applied terminal
 * status — usually `Ready` — so a caller believed its app ID had taken effect while ads kept serving
 * under the first one. These cases pin the replacement, and guard the trap that makes the fix
 * dangerous if done naively (see `publishes the true status` below).
 */
class AppliedConfigurationDecisionTest {

    private val identity = AdInitializationConfigIdentity(
        platformAppId = "ca-app-pub-1111111111111111~1111111111",
        globalRequestConfiguration = GlobalRequestConfiguration(),
    )
    private val policy = NativeAdMemoryPolicy()

    private fun decide(
        appliedIdentity: AdInitializationConfigIdentity? = identity,
        requestedIdentity: AdInitializationConfigIdentity = identity,
        configuredNativePolicy: NativeAdMemoryPolicy? = policy,
        requestedNativePolicy: NativeAdMemoryPolicy = policy,
        appliedTerminalStatus: AdManagerStatus? = AdManagerStatus.Ready,
        currentStatus: AdManagerStatus = AdManagerStatus.Ready,
    ) = appliedConfigurationDecision(
        appliedIdentity = appliedIdentity,
        requestedIdentity = requestedIdentity,
        configuredNativePolicy = configuredNativePolicy,
        requestedNativePolicy = requestedNativePolicy,
        appliedTerminalStatus = appliedTerminalStatus,
        currentStatus = currentStatus,
    )

    @Test
    fun `no applied identity means the caller should initialize`() {
        assertIs<AppliedConfigurationDecision.NotApplied>(decide(appliedIdentity = null))
    }

    @Test
    fun `an identical request is idempotent and republishes the terminal status`() {
        val decision = assertIs<AppliedConfigurationDecision.Accepted>(decide())
        assertEquals(AdManagerStatus.Ready, decision.publish)
    }

    @Test
    fun `an identical request before a terminal status leaves the status alone`() {
        val decision = assertIs<AppliedConfigurationDecision.Accepted>(
            decide(appliedTerminalStatus = null, currentStatus = AdManagerStatus.Initializing)
        )
        assertNull(decision.publish, "nothing terminal was recorded, so nothing should be published")
    }

    @Test
    fun `a different app id is a non-retryable conflict`() {
        val decision = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(requestedIdentity = identity.copy(platformAppId = "ca-app-pub-2222222222222222~2222222222"))
        )
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, decision.rejection.error.code)
        assertFalse(
            decision.rejection.retryable,
            "the singleton cannot be reconfigured, so retrying can never succeed"
        )
    }

    @Test
    fun `a different global request configuration is a conflict`() {
        val decision = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(
                requestedIdentity = identity.copy(
                    globalRequestConfiguration = GlobalRequestConfiguration(appMuted = true)
                )
            )
        )
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, decision.rejection.error.code)
        assertFalse(decision.rejection.retryable)
    }

    @Test
    fun `a different native ad memory policy is a conflict`() {
        val decision = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(requestedNativePolicy = policy.copy(softLimit = 2, hardLimit = 3))
        )
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, decision.rejection.error.code)
    }

    @Test
    fun `an unbound native policy is not a conflict`() {
        // Native ads may simply never have been used, in which case any requested policy is fine.
        assertIs<AppliedConfigurationDecision.Accepted>(
            decide(configuredNativePolicy = null, requestedNativePolicy = policy.copy(softLimit = 2))
        )
    }

    @Test
    fun `a conflict publishes the true status and rejects only the caller`() {
        val decision = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(requestedIdentity = identity.copy(platformAppId = "other"))
        )
        // THE trap. adRequestBlockedError() blocks every ad request while the status is not Ready,
        // so publishing the rejection would take down ad serving process-wide for the caller whose
        // configuration was actually accepted. The rejection goes to the return value only.
        assertEquals(AdManagerStatus.Ready, decision.publish)
        assertIs<AdManagerStatus.Failed>(decision.rejection)
    }

    @Test
    fun `a conflict reports the true status even when initialization had failed`() {
        val failed = AdManagerStatus.Failed(AdError.message("native init failed"), retryable = true)
        val decision = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(
                requestedIdentity = identity.copy(platformAppId = "other"),
                appliedTerminalStatus = failed,
            )
        )
        assertEquals(failed, decision.publish)
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, decision.rejection.error.code)
    }

    @Test
    fun `the conflict message names the facet that conflicted`() {
        val appId = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(requestedIdentity = identity.copy(platformAppId = "other-app-id"))
        )
        assertEquals(true, appId.reason.contains("other-app-id"))

        val global = assertIs<AppliedConfigurationDecision.Conflict>(
            decide(
                requestedIdentity = identity.copy(
                    globalRequestConfiguration = GlobalRequestConfiguration(appVolume = 0.5f)
                )
            )
        )
        assertEquals(true, global.reason.contains("global request configuration"))
    }

    // --- native handoff ownership -----------------------------------------------------------

    private fun identity(appId: String) = AdInitializationConfigIdentity(
        platformAppId = appId,
        globalRequestConfiguration = GlobalRequestConfiguration(),
    )

    @Test
    fun `no handoff yet permits initialization`() {
        assertEquals(
            NativeHandoffDecision.Proceed,
            nativeHandoffDecision(handedOffIdentity = null, requestedIdentity = identity("A")),
        )
    }

    @Test
    fun `a same-identity retry after a timed-out handoff is permitted`() {
        // The load-bearing case for a real GMA hang: the wrapper timed out, nothing was
        // committed, and retrying the SAME configuration is the documented next step.
        assertEquals(
            NativeHandoffDecision.Proceed,
            nativeHandoffDecision(handedOffIdentity = identity("A"), requestedIdentity = identity("A")),
        )
    }

    @Test
    fun `a different-identity retry after a handoff is refused not silently accepted`() {
        val decision = nativeHandoffDecision(
            handedOffIdentity = identity("A"),
            requestedIdentity = identity("B"),
        )

        // Pins the invariant: a timeout releases the WAITER; it cannot undo an irreversible
        // native handoff, so the wrapper must never let a second configuration claim ownership.
        val refuse = assertIs<NativeHandoffDecision.Refuse>(decision)
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, refuse.rejection.error.code)
        assertFalse(
            refuse.rejection.retryable,
            "the native singleton cannot be reconfigured, so retrying cannot help",
        )
    }
}
