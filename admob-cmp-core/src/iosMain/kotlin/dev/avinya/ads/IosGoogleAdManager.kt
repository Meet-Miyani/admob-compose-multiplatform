@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdSize
import GoogleMobileAds.GADAdSizeFromCGSize
import GoogleMobileAds.GADAdSizeFluid
import GoogleMobileAds.GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight
import GoogleMobileAds.GADLargeAnchoredAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADMobileAds
import dev.avinya.ads.internal.AdRequestAdmission
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.deriveAdmission
import dev.avinya.ads.internal.ConsentSessionState
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.nativead.IosNativeAdPlatform
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.coroutines.cancellation.CancellationException
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
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSRecursiveLock
import dev.avinya.ads.internal.AppliedConfigurationDecision
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.appliedConfigurationDecision
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.dispatchAfterInitializeHooks

private data class AdSlotKey(val placementId: String, val format: AdFormat)

private object IosAdManagerHolder {
    val instance: IosGoogleAdManager = IosGoogleAdManager()
}

/** Public entry point for the process-wide iOS [AdManager] singleton. */
public object IosAdMob {
    public val manager: AdManager get() = IosAdManagerHolder.instance
}

internal class IosGoogleAdManager : AdManager, FullScreenPresenceAware {
    private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Idle)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 128)

    // Guards the controller registries + collision map. Android uses synchronized(registryLock);
    // an NSRecursiveLock is the iOS equivalent so concurrent factory calls can't create two
    // controllers for the same placement or corrupt the maps.
    private val registryLock = NSRecursiveLock()
    private inline fun <T> registry(block: () -> T): T {
        registryLock.lock()
        try {
            return block()
        } finally {
            registryLock.unlock()
        }
    }

    // Process-wide full-screen presentation count + derived signal (see FullScreenPresenceAware).
    // Observational only — admission is decided by fullScreenArbiter below. Updated from
    // FullScreenSlotCore via onPresentationChanged.
    private val presenceLock = NSRecursiveLock()
    private var presenceCount = 0
    private val _isFullScreenPresenting = MutableStateFlow(false)
    override val isFullScreenPresenting: StateFlow<Boolean> = _isFullScreenPresenting

    // Process-wide admission gate — see the Android twin. Shared by every full-screen slot
    // this manager creates and probed by AppOpenAdCoordinator.
    override val fullScreenArbiter: FullScreenPresentationArbiter = FullScreenPresentationArbiter()
    private fun onPresentationChanged(delta: Int) {
        presenceLock.lock()
        try {
            presenceCount = (presenceCount + delta).coerceAtLeast(0)
            _isFullScreenPresenting.value = presenceCount > 0
        } finally {
            presenceLock.unlock()
        }
    }

    override val status: StateFlow<AdManagerStatus> = _status
    override val events: SharedFlow<AdEvent> = _events
    private val consentSession = ConsentSessionState()
    override val consent: ConsentController = IosConsentController(resume@{ config ->
        val mode = consentSession.modeForPrivacyOptionsResume() ?: return@resume
        initialize(config, mode)
    })
    private val iosDiagnostics = IosAdDiagnostics()
    override val diagnostics: AdDiagnostics = iosDiagnostics
    override val tracking: AdTrackingController = IosTrackingController

    private val banners = mutableMapOf<String, BannerAdController>()
    private val slots = mutableMapOf<AdSlotKey, FullScreenAdController>()
    private val nativeManager = NativeAdManagerImpl(
        policy = null,
        platform = IosNativeAdPlatform(),
        canRequestAds = { adRequestBlockedError() == null },
        eventSink = { _events.tryEmit(it) },
    )
    override val nativeAds: NativeAdManager = nativeManager

    /** Binds the deferred native facade only after this config has initialized GMA. */
    internal fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        nativeManager.configure(config.nativeAdMemoryPolicy)
    }

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
    private val nativeInitializationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingInitialization: InitializationAttempt? = null
    private var nativeInitialization: NativeInitialization? = null
    private var nativeInitializationGeneration = 0L

    // Live admission state — see the Android twin. Replaces the sticky
    // consentGateSatisfied latch so revocation immediately closes the gate.
    private val _admission = MutableStateFlow(AdRequestAdmission.NotGathered)

    // iOS nativeInitializationScope is already Dispatchers.Main; this scope is kept
    // separate anyway so the collector's lifetime is independent of initialization.
    private val admissionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        admissionScope.launch {
            consent.canRequestAds.collectLatest { canRequest ->
                val next = consentSession.admission(canRequest)
                val previous = _admission.value
                if (previous == next) return@collectLatest
                _admission.value = next
                if (previous == AdRequestAdmission.Allowed && next == AdRequestAdmission.Revoked) {
                    AdLogger.w("iOS consent revoked. Closing ad request gate and purging cached inventory.")
                    purgeOnRevocation()
                }
            }
        }
    }

    private var appliedConfigIdentity: AdInitializationConfigIdentity? = null
    private var appliedTerminalStatus: AdManagerStatus? = null

    override suspend fun initialize(
        config: AdConfig,
        consentMode: ConsentMode
    ): AdManagerStatus {
        val ownedConfig = config.ownedSnapshot()
        val requestedIdentity = ownedConfig.initializationIdentity(ownedConfig.iosAppId)
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
        AdLogger.i("iOS initialize requested. consentMode=$consentMode")
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
            // rely on cached state. Once this manager reaches Ready, equivalent calls
            // are handled above and must not regress status or rerun consent, hooks,
            // or the process-wide GMA singleton.
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
            AdLogger.i("iOS consent gate complete. canRequestAds=$effectiveCanRequest mode=$consentMode")
            if (!effectiveCanRequest) {
                AdLogger.w("iOS initialize deferred because consent does not allow ad requests yet.")
                val result = AdManagerStatus.ConsentRequired.also { _status.value = it }
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
                AdLogger.w("iOS MobileAds configuration conflict. ${decision.reason}")
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
        val requestedIdentity = config.initializationIdentity(config.iosAppId)
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
            AdLogger.i("iOS MobileAds initialization succeeded.")
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
        // result. AfterMobileAdsInitialize dispatches here, before Applied/Ready is
        // THAT detachment is what makes AfterMobileAdsInitialize fire exactly once whenever
        // native init succeeds; an earlier comment credited the hook's position *before*
        // publication, which was wrong and cost correctness elsewhere.
        //
        // Native acceptance is now committed BEFORE the hook runs. A throwing publisher hook
        // used to leave appliedConfigIdentity null while GMA was already initialized, so a
        // retry with a different app ID sailed past appliedOutcome() and tried to reconfigure
        // an immutable process singleton. The tradeoff is that a concurrent same-identity
        // initialize() may observe Ready while the hook is still running -- strictly better
        // than desynchronizing the wrapper from native reality.
        val completion = nativeInitializationScope.async(start = CoroutineStart.LAZY) {
            val result = try {
                GADMobileAds.sharedInstance.requestConfiguration.let { requestConfig ->
                    requestedIdentity.globalRequestConfiguration.applyTo(requestConfig)
                }
                // Bounded: GMA can accept start() and never invoke the handler, which used to
                // leave initialize() suspended forever. A timeout is NOT a CancellationException
                // (see awaitNativeCallback), so it reaches the catch below as a real failure and
                // leaves the identity uncommitted -- making a retry the correct next step.
                awaitNativeCallback(
                    operation = "GADMobileAds.start",
                    timeout = InitializationTimeouts.nativeInitialize
                ) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        GADMobileAds.sharedInstance.startWithCompletionHandler { status ->
                            val adapterStates = status?.adapterStatusesByClassName
                            if (adapterStates != null) {
                                adapterStates.forEach { (name, _) ->
                                    AdLogger.d("iOS adapter '${name}'")
                                }
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                config.globalRequestConfiguration.publisherFirstPartyIdEnabled?.let {
                    AdLogger.d("iOS publisherFirstPartyIdEnabled is Ad Manager only, skipping")
                }
                config.globalRequestConfiguration.appMuted?.let {
                    GADMobileAds.sharedInstance.applicationMuted = it
                }
                config.globalRequestConfiguration.appVolume?.let {
                    GADMobileAds.sharedInstance.applicationVolume = it.coerceIn(0f, 1f)
                }
                // Already on nativeInitializationScope (Dispatchers.Main). Captured after
                // startWithCompletionHandler returns so adapter statuses are populated,
                // and before the After hook so a publisher hook can read them.
                iosDiagnostics.captureSnapshotOnMain()
                mobileAdsInitializationMutex.withLock {
                    // Native GMA is initialized at this point and can never be reconfigured, so
                    // the identity commit below must be unconditional. configureNativeAds can
                    // throw -- NativeAdManagerImpl.configure has a check(existing == null) --
                    // which is the same trap as a throwing hook, one step earlier.
                    try {
                        configureNativeAdsAfterAcceptedInitialization(config)
                    } catch (t: Throwable) {
                        AdLogger.e(
                            "Failed to bind the native ad memory policy after iOS MobileAds " +
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
        AdLogger.e("iOS MobileAds initialization failed.", failure)
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
        AdLogger.e("iOS MobileAds initialization failed.", failure)
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
     * revoked. Live full-screen presentations are deliberately NOT torn down — the
     * ad is already owned by SDK callbacks. See the Android twin for the full
     * rationale.
     */
    private fun purgeOnRevocation() {
        var bannersSnapshot: List<BannerAdController> = emptyList()
        var slotsSnapshot: List<FullScreenAdController> = emptyList()
        registry {
            bannersSnapshot = banners.values.toList()
            slotsSnapshot = slots.values.toList()
        }
        bannersSnapshot.forEach { it.clear() }
        slotsSnapshot.forEach { it.clear() }
        nativeManager.onConsentRevoked()
        AdLogger.i(
            "iOS revocation purge complete. banners=${bannersSnapshot.size} " +
                "fullScreen=${slotsSnapshot.size} nativeSessions=${nativeAds.state.value.activeSessions}"
        )
    }

    override fun banner(placement: AdPlacement): BannerAdController = registry {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Banner) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Banner factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        banners.getOrPut(ownedPlacement.id) {
            AdLogger.d("iOS banner controller created. placement=${ownedPlacement.id}")
            IosBannerAdController(ownedPlacement, _events, ::adRequestBlockedError)
        }
    }

    override fun interstitial(placement: AdPlacement): InterstitialAdController = registry {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Interstitial) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to an Interstitial factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { IosInterstitialSlot(ownedPlacement, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter) } as InterstitialAdController
    }

    override fun rewarded(placement: AdPlacement): RewardedAdController = registry {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Rewarded) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Rewarded factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { IosRewardedSlot(ownedPlacement, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter) } as RewardedAdController
    }

    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = registry {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.RewardedInterstitial) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a RewardedInterstitial factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { IosRewardedInterstitialSlot(ownedPlacement, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter) } as RewardedInterstitialAdController
    }

    override fun appOpen(placement: AdPlacement): AppOpenAdController = registry {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.AppOpen) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to an AppOpen factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { IosAppOpenSlot(ownedPlacement, _events, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter) } as AppOpenAdController
    }
}

@Suppress("UNCHECKED_CAST")
internal fun AdSizePolicy.toIOSAdSize(widthDp: Int): CValue<GADAdSize> = when (this) {
    is AdSizePolicy.LargeAnchoredAdaptive -> GADLargeAnchoredAdaptiveBannerAdSizeWithWidth(widthDp.toDouble()) as CValue<GADAdSize>
    is AdSizePolicy.InlineAdaptive -> maxHeightDp?.let {
        GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight(widthDp.toDouble(), it.toDouble()) as CValue<GADAdSize>
    } ?: GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth(widthDp.toDouble()) as CValue<GADAdSize>
    is AdSizePolicy.Fixed -> GADAdSizeFromCGSize(CGSizeMake(widthDp.toDouble(), heightDp.toDouble())) as CValue<GADAdSize>
    is AdSizePolicy.Fluid -> GADAdSizeFluid as CValue<GADAdSize>
}
