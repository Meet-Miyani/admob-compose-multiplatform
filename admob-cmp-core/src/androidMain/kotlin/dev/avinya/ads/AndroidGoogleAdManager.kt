package dev.avinya.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import dev.avinya.ads.internal.AdRequestAdmission
import dev.avinya.ads.internal.AppliedConfigurationDecision
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.appliedConfigurationDecision
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.awaitHost
import dev.avinya.ads.internal.dispatchAfterInitializeHooks
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.deriveAdmission
import dev.avinya.ads.internal.ConsentSessionState
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.emitOrLogDrop
import dev.avinya.ads.nativead.AndroidNativeAdPlatform
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.OnAdInspectorClosedListener
import com.google.android.libraries.ads.mobile.sdk.initialization.AdapterStatus
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class AdSlotKey(val placementId: String, val format: AdFormat)

internal class AndroidGoogleAdManager(
    val appContext: Context,
    val activityProvider: () -> Activity?
) : AdManager, FullScreenPresenceAware {
    private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Idle)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 128)
    private val registryLock = Any()

    // Count of full-screen ads currently presenting across all slots. Observational only —
    // admission is decided by fullScreenArbiter below, not by this counter. Updated from
    // FullScreenSlotCore via onPresentationChanged. Guarded by presenceLock since shows can
    // settle on different threads.
    private val presenceLock = Any()
    private var presenceCount = 0
    private val _isFullScreenPresenting = MutableStateFlow(false)
    override val isFullScreenPresenting: StateFlow<Boolean> = _isFullScreenPresenting

    // The process-wide admission gate. AdMob.manager() is a per-process singleton, so this
    // instance is the whole process's arbiter. Every full-screen slot this manager creates
    // shares it, and AppOpenAdCoordinator probes it through FullScreenPresenceAware.
    override val fullScreenArbiter: FullScreenPresentationArbiter = FullScreenPresentationArbiter()

    /**
     * Shared by every full-screen slot, so a dismissed ad reverts audio to the global state this
     * manager actually applied to GMA rather than to hardcoded defaults.
     *
     * The supplier is deliberately lazy: slots are created before `initialize()` completes, and
     * `null` correctly means "the host configured nothing, so GMA's own defaults apply".
     */
    private val fullScreenAudioController =
        AndroidFullScreenAudioController { appliedConfigIdentity?.globalRequestConfiguration }

    private fun onPresentationChanged(delta: Int) {
        synchronized(presenceLock) {
            presenceCount = (presenceCount + delta).coerceAtLeast(0)
            _isFullScreenPresenting.value = presenceCount > 0
        }
    }

    override val status: StateFlow<AdManagerStatus> = _status
    override val events: SharedFlow<AdEvent> = _events
    private val consentSession = ConsentSessionState()
    override val consent: ConsentController =
        AndroidConsentController(activityProvider, appContext, resume@{ config ->
            val mode = consentSession.modeForPrivacyOptionsResume() ?: return@resume
            initialize(config, mode)
        })
    private val androidDiagnostics = AndroidAdDiagnostics(activityProvider)
    override val diagnostics: AdDiagnostics = androidDiagnostics
    override val tracking: AdTrackingController = AndroidTrackingController

    // Detects placement-id collisions: the same id used with a different ad unit /
    // format / config silently reusing the first controller is a programming error.
    private val registeredPlacements = mutableMapOf<String, AdPlacement>()

    private fun checkPlacementCollision(placement: AdPlacement) {
        val existing = registeredPlacements.getOrPut(placement.id) { placement }
        check(existing == placement) {
            "AdPlacement id '${placement.id}' is already registered with different configuration " +
                "(existing=$existing, requested=$placement). Placement ids must be unique."
        }
    }

    private val banners = mutableMapOf<String, BannerAdController>()
    private val slots = mutableMapOf<AdSlotKey, FullScreenAdController>()
    private val nativeManager = NativeAdManagerImpl(
        policy = null,
        platform = AndroidNativeAdPlatform(),
        canRequestAds = { adRequestBlockedError() == null },
        // Routed through the shared drop-aware helper like every other emitter. This path called
        // tryEmit and discarded the Boolean -- the exact pattern emitOrLogDrop was introduced to
        // remove -- so a dropped native event was silent, precisely where batching makes bursts
        // most likely.
        eventSink = { _events.emitOrLogDrop(it, "NativeAds") },
    )
    override val nativeAds: NativeAdManager = nativeManager

    /** Binds the deferred native facade only after this config has initialized GMA. */
    internal fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        nativeManager.configure(config.nativeAdMemoryPolicy)
    }
    private data class InitializationAttempt(
        val identity: AdInitializationConfigIdentity,
        val consentMode: ConsentMode,
        val completion: CompletableDeferred<AdManagerStatus>
    )
    private sealed interface NativeInitializationResult {
        data object Applied : NativeInitializationResult
        data class Failed(val failure: Throwable) : NativeInitializationResult
    }
    private data class NativeInitialization(
        val generation: Long,
        val identity: AdInitializationConfigIdentity,
        val completion: Deferred<NativeInitializationResult>
    )

    // Guards registration of the one in-flight consent -> GMA sequence. The
    // narrower SDK mutex protects the native-applied identity and terminal state.
    private val initializationStateMutex = Mutex()
    private val mobileAdsInitializationMutex = Mutex()
    // Main.immediate, matching iOS (IosGoogleAdManager.kt:129). Everything this scope
    // runs — MobileAds.initialize and the global request-configuration writes that
    // follow it — is a GMA call, and GMA calls belong on the main thread (CLAUDE.md
    // invariant #5). The scope stays a detached SupervisorJob so cancelling one
    // initialize() caller still cannot interrupt initialization; only the dispatcher
    // changes here.
    private val nativeInitializationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingInitialization: InitializationAttempt? = null
    private var nativeInitialization: NativeInitialization? = null
    private var nativeInitializationGeneration = 0L
    // Live admission state. Recomputed whenever consent mode or canRequestAds changes,
    // so a revocation through the privacy options form immediately closes the gate.
    // Replaces the previous sticky consentGateSatisfied latch.
    private val _admission = MutableStateFlow(AdRequestAdmission.NotGathered)

    // Own scope, deliberately NOT nativeInitializationScope (which now uses
    // Dispatchers.Main.immediate, matching iOS). Main.immediate matches the dispatcher every
    // GMA/UMP call already uses.
    private val admissionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        admissionScope.launch {
            consent.canRequestAds.collectLatest { canRequest ->
                val next = consentSession.admission(canRequest)
                val previous = _admission.value
                if (previous == next) return@collectLatest
                _admission.value = next
                if (previous == AdRequestAdmission.Allowed && next == AdRequestAdmission.Revoked) {
                    AdLogger.w("Android consent revoked. Closing ad request gate and purging cached inventory.")
                    purgeOnRevocation()
                }
            }
        }
    }

    @Volatile
    private var appliedConfigIdentity: AdInitializationConfigIdentity? = null
    @Volatile
    private var appliedTerminalStatus: AdManagerStatus? = null

    override suspend fun initialize(
        config: AdConfig,
        consentMode: ConsentMode
    ): AdManagerStatus {
        val ownedConfig = config.ownedSnapshot()
        val requestedIdentity = ownedConfig.initializationIdentity(ownedConfig.androidAppId)
        while (true) {
            var leadsAttempt = false
            var nativeCompletion: Deferred<NativeInitializationResult>? = null
            val attempt = initializationStateMutex.withLock {
                pendingInitialization?.let { return@withLock it }
                nativeCompletion = mobileAdsInitializationMutex.withLock {
                    nativeInitialization?.completion
                }
                if (nativeCompletion != null) return@withLock null
                appliedOutcome(requestedIdentity, ownedConfig.nativeAdMemoryPolicy)?.let { return it }
                InitializationAttempt(
                    identity = requestedIdentity,
                    consentMode = consentMode,
                    completion = CompletableDeferred()
                ).also {
                    pendingInitialization = it
                    leadsAttempt = true
                }
            }

            val completionToAwait = nativeCompletion
            if (completionToAwait != null) {
                // Await outside admission so callers remain cancellable and no
                // coordinator mutex is held while the native reservation settles.
                completionToAwait.await()
                continue
            }
            val admittedAttempt = checkNotNull(attempt)
            if (leadsAttempt) {
                return runInitializationAttempt(admittedAttempt, ownedConfig, consentMode)
            }

            val equivalentAttempt =
                admittedAttempt.identity == requestedIdentity && admittedAttempt.consentMode == consentMode
            val result = try {
                admittedAttempt.completion.await()
            } catch (leaderCancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (equivalentAttempt) throw leaderCancellation
                continue
            }
            if (equivalentAttempt) return result
            if (result == AdManagerStatus.Ready) {
                appliedOutcome(requestedIdentity, config.nativeAdMemoryPolicy)?.let { return it }
            }
            // A distinct request waited for the process-wide slot but the leader
            // did not initialize GMA. Register a new attempt for its own semantics.
        }
    }

    private suspend fun runInitializationAttempt(
        attempt: InitializationAttempt,
        config: AdConfig,
        consentMode: ConsentMode
    ): AdManagerStatus {
        AdLogger.i("Android initialize requested. consentMode=$consentMode")
        config.testModeWarningOrNull()?.let(AdLogger::w)

        val previousStatus = _status.value
        _status.value = AdManagerStatus.Initializing

        return try {
            when (awaitAbandonedNativeInitialization()) {
                NativeInitializationResult.Applied -> {
                    val result = appliedOutcome(attempt.identity, config.nativeAdMemoryPolicy) ?: _status.value
                    completeAttempt(attempt, result)
                    return result
                }
                is NativeInitializationResult.Failed -> {
                    appliedOutcome(attempt.identity, config.nativeAdMemoryPolicy)?.let { result ->
                        completeAttempt(attempt, result)
                        return result
                    }
                }
                null -> Unit
            }
            // UMP guidance: request a consent info update on every app launch; do not
            // rely on the previous session's cached state. Once this manager reaches
            // Ready, equivalent calls are handled above and must not regress status or
            // rerun consent, hooks, or the process-wide GMA singleton.
            when (consentMode) {
                ConsentMode.GatherBeforeInitialize -> consent.gatherConsent(config)
                ConsentMode.InitializeOnlyIfAlreadyAllowed -> consent.requestConsentInfoUpdate(config)
                ConsentMode.SkipConsent -> Unit
            }
            val effectiveCanRequest = withContext(Dispatchers.Main.immediate) {
                consentSession.recordCompletedGate(consentMode)
                val current = if (consentMode == ConsentMode.SkipConsent) true else consent.canRequestAds.value
                val newAdmission = consentSession.admission(current)
                _admission.value = newAdmission
                current
            }

            AdLogger.i("Android consent gate complete. canRequestAds=$effectiveCanRequest mode=$consentMode")
            if (!effectiveCanRequest) {
                AdLogger.w("Android initialize deferred because consent does not allow ad requests yet.")
                val result = AdManagerStatus.ConsentRequired.also {
                    _status.value = it
                }
                completeAttempt(attempt, result)
                return result
            }

            initializeMobileAds(config).also { completeAttempt(attempt, it) }
        } catch (cancellation: CancellationException) {
            cancelAttempt(attempt, cancellation, previousStatus)
            throw cancellation
        } catch (failure: Throwable) {
            initializationFailed(failure).also { completeAttempt(attempt, it) }
        }
    }

    private suspend fun completeAttempt(
        attempt: InitializationAttempt,
        result: AdManagerStatus
    ) = withContext(NonCancellable) {
        initializationStateMutex.withLock {
            if (pendingInitialization === attempt) pendingInitialization = null
        }
        attempt.completion.complete(result)
    }

    private suspend fun cancelAttempt(
        attempt: InitializationAttempt,
        cancellation: CancellationException,
        previousStatus: AdManagerStatus
    ) = withContext(NonCancellable) {
        _status.value = appliedTerminalStatus() ?: previousStatus
        initializationStateMutex.withLock {
            if (pendingInitialization === attempt) pendingInitialization = null
        }
        attempt.completion.completeExceptionally(cancellation)
    }

    private suspend fun appliedOutcome(
        requestedIdentity: AdInitializationConfigIdentity,
        requestedNativeAdMemoryPolicy: NativeAdMemoryPolicy,
    ): AdManagerStatus? = mobileAdsInitializationMutex.withLock {
        val decision = appliedConfigurationDecision(
            appliedIdentity = appliedConfigIdentity,
            requestedIdentity = requestedIdentity,
            configuredNativePolicy = nativeManager.configuredPolicyOrNull(),
            requestedNativePolicy = requestedNativeAdMemoryPolicy,
            appliedTerminalStatus = appliedTerminalStatus,
            currentStatus = _status.value,
        )
        when (decision) {
            is AppliedConfigurationDecision.NotApplied -> null
            is AppliedConfigurationDecision.Accepted ->
                decision.publish?.let(::publishAppliedTerminalLocked)
            is AppliedConfigurationDecision.Conflict -> {
                AdLogger.w("Android MobileAds configuration conflict. ${decision.reason}")
                // Publish the TRUE status, return the rejection. Publishing the rejection would
                // make adRequestBlockedError() block every ad request process-wide, breaking the
                // caller whose configuration was actually accepted.
                publishAppliedTerminalLocked(decision.publish)
                decision.rejection
            }
        }
    }

    private suspend fun appliedTerminalStatus(): AdManagerStatus? =
        mobileAdsInitializationMutex.withLock { appliedTerminalStatus }

    private suspend fun awaitAbandonedNativeInitialization(): NativeInitializationResult? {
        val operation = mobileAdsInitializationMutex.withLock { nativeInitialization } ?: return null
        return operation.completion.await()
    }

    private suspend fun initializeMobileAds(config: AdConfig): AdManagerStatus {
        val requestedIdentity = config.initializationIdentity(config.androidAppId)
        appliedOutcome(requestedIdentity, config.nativeAdMemoryPolicy)?.let { return it }

        var operation = mobileAdsInitializationMutex.withLock { nativeInitialization }
        if (operation == null) {
            withContext(Dispatchers.Main.immediate) {
                config.dispatchInitializationHooks(AdInitializationPhase.BeforeMobileAdsInitialize)
            }
            operation = startNativeInitialization(config, requestedIdentity)
        }

        val previousStatus = _status.value
        _status.value = AdManagerStatus.Initializing
        return try {
            when (val nativeResult = operation.completion.await()) {
                is NativeInitializationResult.Failed -> return initializationFailed(nativeResult.failure)
                NativeInitializationResult.Applied -> Unit
            }
            AdLogger.i("Android MobileAds initialization succeeded.")
            publishAppliedTerminal(previousStatus)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { publishAppliedTerminal(previousStatus) }
            throw cancellation
        } catch (failure: Throwable) {
            recordAppliedFailure(requestedIdentity, failure)
        }
    }

    private suspend fun startNativeInitialization(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity
    ): NativeInitialization = mobileAdsInitializationMutex.withLock {
        nativeInitialization?.let { return@withLock it }
        appliedConfigIdentity?.let {
            return@withLock completedNativeInitialization(it)
        }

        val generation = ++nativeInitializationGeneration
        // Runs on nativeInitializationScope (a detached SupervisorJob scope), not on any
        // individual caller's coroutine, so cancelling one initialize() caller can never
        // interrupt this operation — every caller (leader and followers) only awaits its
        // result. THAT detachment is what makes AfterMobileAdsInitialize fire exactly once
        // whenever native init succeeds; an earlier comment here credited the hook's position
        // *before* publication, which was wrong and cost correctness elsewhere.
        //
        // Native acceptance is now committed BEFORE the hook runs. A throwing publisher hook
        // used to leave appliedConfigIdentity null while GMA was already initialized, so a
        // retry with a different app ID sailed past appliedOutcome() and tried to reconfigure
        // an immutable process singleton. The tradeoff is that a concurrent same-identity
        // initialize() may observe Ready while the hook is still running — strictly better
        // than desynchronizing the wrapper from native reality.
        val completion = nativeInitializationScope.async(start = CoroutineStart.LAZY) {
            val result = try {
                AdLogger.d("Android initializing MobileAds with global request configuration.")
                val initializationConfig = InitializationConfig.Builder(config.androidAppId)
                    .setRequestConfiguration(requestedIdentity.globalRequestConfiguration.toAndroidRequestConfiguration())
                    .build()
                withContext(Dispatchers.IO) {
                    // Bounded: GMA can accept the call and never invoke the callback, which used to
                    // leave initialize() suspended forever. A timeout is NOT a CancellationException
                    // (see awaitNativeCallback), so it reaches the catch below as a real failure and
                    // leaves the identity uncommitted — making a retry the correct next step.
                    awaitNativeCallback(
                        operation = "MobileAds.initialize",
                        timeout = InitializationTimeouts.nativeInitialize
                    ) {
                        suspendCancellableCoroutine { continuation ->
                            MobileAds.initialize(appContext, initializationConfig) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                    }
                }
                config.globalRequestConfiguration.publisherFirstPartyIdEnabled?.let {
                    MobileAds.putPublisherFirstPartyIdEnabled(it)
                }
                config.globalRequestConfiguration.appMuted?.let {
                    MobileAds.setUserMutedApp(it)
                }
                config.globalRequestConfiguration.appVolume?.let {
                    MobileAds.setUserControlledAppVolume(it.coerceIn(0f, 1f))
                }
                // Already on nativeInitializationScope (Dispatchers.Main.immediate). Captured
                // after MobileAds.initialize's callback so adapter statuses are populated,
                // and before the After hook so a publisher hook can read them.
                androidDiagnostics.captureSnapshotOnMain()
                mobileAdsInitializationMutex.withLock {
                    // Native GMA is initialized at this point and can never be reconfigured, so
                    // the identity commit below must be unconditional. configureNativeAds can
                    // throw -- NativeAdManagerImpl.configure has a check(existing == null) -- which
                    // is the same trap as a throwing hook, one step earlier.
                    try {
                        configureNativeAdsAfterAcceptedInitialization(config)
                    } catch (t: Throwable) {
                        AdLogger.e(
                            "Failed to bind the native ad memory policy after Android MobileAds " +
                                "initialization. GMA stays initialized and Ready; native ad " +
                                "capacity keeps its previously configured policy.",
                            t
                        )
                    }
                    appliedConfigIdentity = requestedIdentity
                    appliedTerminalStatus = AdManagerStatus.Ready
                }
                // AFTER the commit, and isolated: a publisher hook is host code, so its failure is
                // reported but must never make the native singleton look unapplied.
                dispatchAfterInitializeHooks(config)
                NativeInitializationResult.Applied
            } catch (failure: Throwable) {
                NativeInitializationResult.Failed(failure)
            }
            withContext(NonCancellable) {
                mobileAdsInitializationMutex.withLock {
                    if (nativeInitialization?.generation == generation) nativeInitialization = null
                    if (result is NativeInitializationResult.Failed && appliedConfigIdentity == requestedIdentity) {
                        val failed = failedInitializationStatus(result.failure)
                        appliedTerminalStatus = failed
                        publishAppliedTerminalLocked(failed)
                    }
                }
            }
            result
        }
        NativeInitialization(generation, requestedIdentity, completion).also {
            nativeInitialization = it
            completion.start()
        }
    }

    private fun completedNativeInitialization(
        identity: AdInitializationConfigIdentity
    ): NativeInitialization = NativeInitialization(
        generation = nativeInitializationGeneration,
        identity = identity,
        completion = CompletableDeferred(NativeInitializationResult.Applied)
    )

    private suspend fun publishAppliedTerminal(previousStatus: AdManagerStatus): AdManagerStatus =
        mobileAdsInitializationMutex.withLock {
            publishAppliedTerminalLocked(appliedTerminalStatus ?: previousStatus)
        }

    private fun publishAppliedTerminalLocked(terminal: AdManagerStatus): AdManagerStatus {
        _status.value = terminal
        return terminal
    }

    private suspend fun recordAppliedFailure(
        requestedIdentity: AdInitializationConfigIdentity,
        failure: Throwable
    ): AdManagerStatus {
        AdLogger.e("Android MobileAds initialization failed.", failure)
        val failed = failedInitializationStatus(failure)
        return withContext(NonCancellable) {
            mobileAdsInitializationMutex.withLock {
                if (appliedConfigIdentity == requestedIdentity) {
                    appliedTerminalStatus = failed
                    publishAppliedTerminalLocked(failed)
                }
            }
            failed
        }
    }

    private fun initializationFailed(failure: Throwable): AdManagerStatus {
        AdLogger.e("Android MobileAds initialization failed.", failure)
        return failedInitializationStatus(failure).also { failed -> _status.value = failed }
    }

    private fun failedInitializationStatus(failure: Throwable): AdManagerStatus.Failed =
        AdManagerStatus.Failed(
            error = AdError.message(failure.message ?: "Google Mobile Ads initialization failed."),
            retryable = true
        )

    private fun adRequestBlockedError(): AdError? = when {
        !_admission.value.permitsRequests -> AdError.consentRequired()
        _status.value != AdManagerStatus.Ready -> AdError.sdkNotReady()
        else -> null
    }

    /**
     * Drops cached inventory across every registered controller after consent is
     * revoked. Live full-screen presentations are deliberately NOT torn down: the
     * ad is already owned by SDK callbacks, and destroying it mid-presentation is
     * what caused the presentation-ownership defects fixed earlier (see the
     * cancellation handling in FullScreenSlotCore.show()).
     *
     * Banner and native views may briefly render a destroyed SDK object after this
     * runs; view-state coherence is owned by the banner/native core extraction
     * (sub-project D) and is not addressed here.
     */
    private fun purgeOnRevocation() {
        val (bannersSnapshot, slotsSnapshot) = synchronized(registryLock) {
            Pair(banners.values.toList(), slots.values.toList())
        }
        bannersSnapshot.forEach { it.clear() }
        slotsSnapshot.forEach { it.clear() }
        nativeManager.onConsentRevoked()
        AdLogger.i(
            "Android revocation purge complete. banners=${bannersSnapshot.size} " +
                "fullScreen=${slotsSnapshot.size} nativeSessions=${nativeAds.state.value.activeSessions}"
        )
    }

    override fun banner(placement: AdPlacement): BannerAdController = synchronized(registryLock) {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Banner) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Banner factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        banners.getOrPut(ownedPlacement.id) {
            AdLogger.d("Android banner controller created. placement=${ownedPlacement.id}")
            AndroidBannerAdController(ownedPlacement, _events, ::adRequestBlockedError, activityProvider)
        }
    }

    override fun interstitial(placement: AdPlacement): InterstitialAdController = synchronized(registryLock) {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Interstitial) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to an Interstitial factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { AndroidInterstitialSlot(ownedPlacement, activityProvider, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController) } as InterstitialAdController
    }

    override fun rewarded(placement: AdPlacement): RewardedAdController = synchronized(registryLock) {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Rewarded) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Rewarded factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { AndroidRewardedSlot(ownedPlacement, activityProvider, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController) } as RewardedAdController
    }

    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = synchronized(registryLock) {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.RewardedInterstitial) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a RewardedInterstitial factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { AndroidRewardedInterstitialSlot(ownedPlacement, activityProvider, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController) } as RewardedInterstitialAdController
    }

    override fun appOpen(placement: AdPlacement): AppOpenAdController = synchronized(registryLock) {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.AppOpen) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to an AppOpen factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { AndroidAppOpenSlot(ownedPlacement, activityProvider, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController) } as AppOpenAdController
    }
}

private class AndroidConsentController(
    private val activityProvider: () -> Activity?,
    private val appContext: Context,
    private val onCanRequestAds: suspend (AdConfig) -> Unit
) : ConsentController {
    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacy = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)
    // Retained so showPrivacyOptions() can resume initialization if the user grants
    // consent from the privacy form after an earlier denial.
    private var lastConfig: AdConfig? = null

    override val status: StateFlow<ConsentStatus> = _status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> = _privacy
    override val canRequestAds: StateFlow<Boolean> = _canRequestAds

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        // Owned snapshot, matching AdManager.initialize(): lastConfig outlives this call and is
        // reused if showPrivacyOptions() later resumes initialization, so retaining the caller's
        // object let post-call mutation of its lists/hooks change UMP debug IDs and hook execution.
        val config = config.ownedSnapshot()
        lastConfig = config
        config.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        return withContext(Dispatchers.Main.immediate) {
            // Acquired inside the main hop, not before it: this reads Activity lifecycle state,
            // which is main-thread-owned (invariant 5). It waits rather than failing on the first
            // null because ForegroundStack legitimately empties for a few hundred milliseconds
            // during any Activity handoff.
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            updateWithActivity(activity, config, UserMessagingPlatform.getConsentInformation(appContext))
        }
    }

    /**
     * The UMP info-update sequence, given a host that has already been acquired.
     *
     * Split out so each PUBLIC consent entry point acquires the host exactly once.
     * [gatherConsent] used to call [requestConsentInfoUpdate], which re-acquired it — two waits
     * for one logical operation. Callers are responsible for being on Main.
     */
    private suspend fun updateWithActivity(
        activity: Activity,
        config: AdConfig,
        consentInformation: ConsentInformation,
    ): ConsentStatus {
        val params = buildConsentParams(activity, config)
        // Bounded: this is a non-interactive network round trip, so UMP accepting the call and
        // never calling back used to hang consent admission — and therefore ad serving —
        // indefinitely. Fails CLOSED: _canRequestAds is left false and the status becomes
        // Failed, so no ad request proceeds on an unknown consent state. The consent FORM and
        // privacy options form below stay unbounded on purpose; a person is reading those.
        val error = try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<AdError?> { continuation ->
                    consentInformation.requestConsentInfoUpdate(
                        activity,
                        params,
                        { if (continuation.isActive) continuation.resume(null) },
                        { if (continuation.isActive) continuation.resume(AdError(code = it.errorCode.toString(), message = it.message)) }
                    )
                }
            }
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("Android UMP consent info update timed out.", timeout)
            return fail(timeout.message ?: "UMP consent info update timed out.")
        }
        updatePrivacyState(consentInformation)
        _canRequestAds.value = consentInformation.canRequestAds()
        val status = if (error == null) {
            consentInformationStatus(consentInformation).also { _status.value = it }
        } else if (consentInformation.canRequestAds()) {
            consentInformationStatus(consentInformation).also { _status.value = it }
        } else {
            ConsentStatus.Failed(error).also { _status.value = it }
        }
        return status
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus =
        withContext(Dispatchers.Main.immediate) {
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            // The acquired Activity is reused rather than calling the public
            // requestConsentInfoUpdate, which would wait for a host a second time. The snapshot
            // and hook dispatch that entry point performs are replicated here so behaviour is
            // identical.
            val ownedConfig = config.ownedSnapshot()
            lastConfig = ownedConfig
            ownedConfig.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
            val update = updateWithActivity(activity, ownedConfig, consentInformation)
            if (update is ConsentStatus.Failed && !consentInformation.canRequestAds()) return@withContext update
            suspendCancellableCoroutine<Unit> { continuation ->
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        AdLogger.w("UMP consent form load/show failed: code=${formError.errorCode} message=${formError.message}")
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            updatePrivacyState(consentInformation)
            _canRequestAds.value = consentInformation.canRequestAds()
            val status = consentInformationStatus(consentInformation).also { _status.value = it }
            status
        }

    override suspend fun showPrivacyOptions(): Boolean {
        val activity = activityProvider() ?: return false
        return withContext(Dispatchers.Main.immediate) {
            val success = suspendCancellableCoroutine { continuation ->
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { if (continuation.isActive) continuation.resume(it == null) }
            }
            // The user may have changed their choices in the form; refresh exposed state
            // so consumers don't read stale consent / canRequestAds values.
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            updatePrivacyState(consentInformation)
            _canRequestAds.value = consentInformation.canRequestAds()
            _status.value = consentInformationStatus(consentInformation)
            // If the user granted consent from the privacy form after an earlier denial,
            // resume initialization — otherwise the SDK would stay uninitialized forever.
            if (_canRequestAds.value) lastConfig?.let { onCanRequestAds(it) }
            success
        }
    }

    override suspend fun resetConsentForDebug(): Boolean {
        val activity = activityProvider() ?: return false
        withContext(Dispatchers.Main.immediate) {
            UserMessagingPlatform.getConsentInformation(appContext).reset()
        }
        _status.value = ConsentStatus.Unknown
        _privacy.value = PrivacyOptionsRequirementStatus.Unknown
        _canRequestAds.value = false
        return true
    }

    private fun fail(message: String): ConsentStatus =
        ConsentStatus.Failed(AdError.message(message)).also { _status.value = it }

    /**
     * The host was absent for the whole `InitializationTimeouts.consentHost` window.
     *
     * Kept as one helper so both consent entry points log identically and the public
     * [ConsentStatus.Failed] message stays byte-identical to what it has always been —
     * only the warning above it is new.
     */
    private fun failNoActivity(): ConsentStatus {
        AdLogger.w(
            "No usable Android Activity for UMP consent after waiting " +
                "${InitializationTimeouts.consentHost}. Consent was not gathered on this attempt; " +
                "the host app should retry."
        )
        return fail("No current Android Activity for UMP consent.")
    }

    private fun updatePrivacyState(consentInformation: ConsentInformation) {
        _privacy.value = when (consentInformation.privacyOptionsRequirementStatus) {
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED -> PrivacyOptionsRequirementStatus.Required
            ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED -> PrivacyOptionsRequirementStatus.NotRequired
            else -> PrivacyOptionsRequirementStatus.Unknown
        }
    }

    // Maps UMP's consent status (distinct from canRequestAds) so the public model
    // can surface NotRequired instead of collapsing everything to Obtained/Required.
    private fun consentInformationStatus(consentInformation: ConsentInformation): ConsentStatus =
        when (consentInformation.consentStatus) {
            ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentStatus.NotRequired
            ConsentInformation.ConsentStatus.OBTAINED -> ConsentStatus.Obtained
            ConsentInformation.ConsentStatus.REQUIRED -> ConsentStatus.Required
            else -> ConsentStatus.Unknown
        }

    private fun buildConsentParams(activity: Activity, config: AdConfig): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        builder.setTagForUnderAgeOfConsent(config.consentTagForUnderAgeOfConsent)
        if (config.testMode && (config.testDeviceIds.isNotEmpty() || config.debugOptions.consentTestDeviceIds.isNotEmpty() || config.debugGeography != ConsentDebugGeography.Disabled)) {
            val debugBuilder = ConsentDebugSettings.Builder(activity)
            (config.debugOptions.consentTestDeviceIds + config.testDeviceIds).distinct().forEach(debugBuilder::addTestDeviceHashedId)
            when (config.debugGeography) {
                ConsentDebugGeography.Disabled -> Unit
                ConsentDebugGeography.Eea -> debugBuilder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                ConsentDebugGeography.NotEea -> debugBuilder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
            }
            builder.setConsentDebugSettings(debugBuilder.build())
        }
        return builder.build()
    }
}

private class AndroidAdDiagnostics(
    private val activityProvider: () -> Activity?
) : AdDiagnostics {
    private val snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)

    private data class DiagnosticsSnapshot(
        val sdkVersion: String?,
        val adapterStatuses: List<AdapterInitializationStatus>
    )

    /**
     * Reads GMA's version and adapter statuses. MUST be called on the main
     * dispatcher. Called once from AndroidGoogleAdManager's initialization block,
     * which runs on nativeInitializationScope (Dispatchers.Main.immediate as of
     * Task 1 of this plan).
     */
    internal fun captureSnapshotOnMain() {
        snapshot.value = DiagnosticsSnapshot(
            sdkVersion = runCatching { MobileAds.getVersion().toString() }.getOrNull(),
            adapterStatuses = runCatching {
                MobileAds.getInitializationStatus().adapterStatusMap.map { (name, status) ->
                    AdapterInitializationStatus(
                        adapterName = name,
                        initialized = status.initializationState == AdapterStatus.InitializationState.COMPLETE,
                        latencyMillis = status.latency.toLong(),
                        description = status.description
                    )
                }
            }.getOrElse { emptyList() }
        )
    }

    override suspend fun openAdInspector(): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            MobileAds.openAdInspector(
                object : OnAdInspectorClosedListener {
                    override fun onAdInspectorClosed(error: com.google.android.libraries.ads.mobile.sdk.common.AdInspectorError?) {
                        continuation.resume(error == null)
                    }
                }
            )
        }
    }

    override suspend fun openDebugMenu(adUnitId: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider() ?: return@withContext false
            MobileAds.openDebugMenu(activity, adUnitId)
            true
        }

    override fun sdkVersion(): String? = snapshot.value?.sdkVersion

    override fun adapterStatuses(): List<AdapterInitializationStatus> =
        snapshot.value?.adapterStatuses ?: emptyList()
}
