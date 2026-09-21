@file:OptIn(InternalAdMobCmpApi::class)

package dev.avinya.ads

import dev.avinya.ads.internal.AdRequestAdmission
import dev.avinya.ads.internal.AppliedConfigurationDecision
import dev.avinya.ads.internal.ConsentSessionState
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.FullScreenStateLock
import dev.avinya.ads.internal.AppIdVerdict
import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.NativeHandoffDecision
import dev.avinya.ads.internal.RequestConfigurationUpdater
import dev.avinya.ads.internal.affectsServedAds
import dev.avinya.ads.internal.appIdPreflightError
import dev.avinya.ads.internal.appIdVerdict
import dev.avinya.ads.internal.appliedConfigurationDecision
import dev.avinya.ads.internal.dispatchAfterInitializeHooks
import dev.avinya.ads.internal.nativeHandoffDecision
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Registry key for a full-screen slot: one controller per (placement id, format) pair. */
internal data class AdSlotKey(val placementId: String, val format: AdFormat)

/**
 * Shared initialization/registration orchestration for [AndroidGoogleAdManager] and
 * [IosGoogleAdManager]. Everything here is platform-independent policy: the initialize()
 * attempt-coalescing loop, the applied-configuration decision, native-init generation
 * tracking and cleanup, terminal status publication, revocation purging, and placement
 * registration/collision detection. A platform subclass implements only the hooks below —
 * the actual GMA/GAD SDK calls, its own consent/diagnostics/tracking controllers, and its own
 * per-format slot constructors (which take different constructor arguments per platform, e.g.
 * Android's slots need an `Activity` provider and an audio controller that iOS has no
 * equivalent for).
 *
 * Keep the fix at this altitude: if a change to the attempt-coalescing loop, the applied-outcome
 * decision, or native-init cleanup seems to require touching `AndroidGoogleAdManager.kt` or
 * `IosGoogleAdManager.kt`, that means shared policy has leaked into a platform subclass, not
 * that this class needs a platform-specific branch.
 */
internal abstract class GoogleAdManagerBase : AdManager, FullScreenPresenceAware, RequestConfigurationUpdater {
    /** "Android" or "iOS" — the only textual difference in this class's log messages. */
    protected abstract val platformTag: String

    private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Idle)
    /** Mutable event sink. Not `_events`: detekt's underscore-prefix convention is reserved for
     * `private` backing fields, and platform subclasses need to emit onto this directly. */
    protected val mutableEvents = MutableSharedFlow<AdEvent>(extraBufferCapacity = 128)
    override val status: StateFlow<AdManagerStatus> = _status
    override val events: SharedFlow<AdEvent> = mutableEvents

    // Guards the controller registries + collision map, and (separately) the process-wide
    // presentation counter. FullScreenStateLock is synchronized(Any()) on Android and an
    // NSRecursiveLock on iOS.
    private val registryLock = FullScreenStateLock()
    private val presenceLock = FullScreenStateLock()

    // Count of full-screen ads currently presenting across all slots. Observational only —
    // admission is decided by fullScreenArbiter below, not by this counter. Updated from
    // FullScreenSlotCore via onPresentationChanged. Guarded by presenceLock since shows can
    // settle on different threads.
    private var presenceCount = 0
    private val _isFullScreenPresenting = MutableStateFlow(false)
    override val isFullScreenPresenting: StateFlow<Boolean> = _isFullScreenPresenting

    // The process-wide admission gate. AdMob.manager() / IosAdMob.manager are per-process
    // singletons, so this instance is the whole process's arbiter. Every full-screen slot this
    // manager creates shares it, and AppOpenAdCoordinator probes it through
    // FullScreenPresenceAware.
    override val fullScreenArbiter: FullScreenPresentationArbiter = FullScreenPresentationArbiter()

    protected fun onPresentationChanged(delta: Int) {
        presenceLock.withLock {
            presenceCount = (presenceCount + delta).coerceAtLeast(0)
            _isFullScreenPresenting.value = presenceCount > 0
        }
    }

    private val consentSession = ConsentSessionState()

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

    /**
     * Shared body of every `banner()` override: validates format, checks for a collision, and
     * caches by placement id. [create] runs only on a genuine cache miss — put any
     * creation-only logging (Android logs one line here) inside it.
     */
    protected fun registerBanner(
        placement: AdPlacement,
        create: (AdPlacement) -> BannerAdController,
    ): BannerAdController = registryLock.withLock {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Banner) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Banner factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        banners.getOrPut(ownedPlacement.id) { create(ownedPlacement) }
    }

    /** Shared body of every full-screen `interstitial()`/`rewarded()`/... override. See [registerBanner]. */
    protected fun registerFullScreenSlot(
        placement: AdPlacement,
        format: AdFormat,
        create: (AdPlacement) -> FullScreenAdController,
    ): FullScreenAdController = registryLock.withLock {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == format) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a $format factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) { create(ownedPlacement) }
    }

    /**
     * Binds the deferred native facade only after this config has initialized GMA.
     *
     * `internal`, not `protected`: `AndroidGoogleAdManagerNativePolicyTest` and
     * `IosGoogleAdManagerNativePolicyTest` call this directly as same-module white-box tests,
     * not from a subclass.
     */
    internal abstract fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig)

    /** Forwards to the platform's own `NativeAdManagerImpl.onConsentRevoked()`, which is not
     * part of the public [dev.avinya.ads.nativead.NativeAdManager] interface and whose concrete
     * type differs per platform. */
    protected abstract fun onNativeConsentRevoked()

    /** `config.androidAppId` or `config.iosAppId` — the one field difference in identity resolution. */
    protected abstract fun appId(config: AdConfig): String

    /**
     * Reads the app ID declared in the platform's own configuration source, independent of
     * [AdConfig] — `AndroidManifest.xml` meta-data on Android, `Info.plist` on iOS. See
     * [DeclaredAppId] for what each outcome means and [appIdConfigurationWarningOrNull] for how
     * it is turned into a warning.
     *
     * `internal`, not `protected`: this class is itself `internal`, so widening this from
     * `protected` costs nothing on the public ABI, and it lets platform host tests exercise the
     * per-platform reader directly instead of only indirectly through a full `initialize()`.
     */
    internal open fun declaredAppId(): DeclaredAppId = DeclaredAppId.Unknown

    /** Human-readable description of where [declaredAppId] reads from, for the configuration warning. */
    internal open val declaredAppIdSource: String = "the platform manifest"

    /**
     * Human-readable description of what actually consumes [declaredAppId] and how that relates
     * to [appId], for the configuration warning. Deliberately per-platform: on Android the
     * consumer is UMP, not GMA itself — see [appIdConfigurationWarningOrNull]'s KDoc for why
     * that distinction matters.
     */
    internal open val declaredAppIdConsumerDescription: String = ""

    /**
     * Whether the platform's own ad SDK resolves its identity from [declaredAppId].
     *
     * True on iOS, where `GADMobileAds` reads `GADApplicationIdentifier` at startup and cannot
     * initialize without it. False on Android, where GMA Next-Gen initializes from
     * `AdConfig.androidAppId` and the manifest value belongs to UMP. This is the difference
     * that makes a missing declaration fatal on one platform and a warning on the other.
     */
    internal open val declaredAppIdRequiredByPlatformSdk: Boolean = false

    /**
     * Reads GMA's/GAD's version and adapter statuses onto the platform's diagnostics object.
     * MUST run on Main, immediately after [initializeMobileAdsNative] returns and before
     * [dev.avinya.ads.internal.dispatchAfterInitializeHooks] runs, so a publisher hook can read
     * populated adapter statuses.
     */
    protected abstract fun captureDiagnosticsSnapshotOnMain()

    /**
     * Applies [requestedIdentity]'s global request configuration to the native SDK, starts it,
     * and applies `publisherFirstPartyIdEnabled`/`appMuted`/`appVolume` from [config] — the one
     * point where this class actually touches GMA (Android) or GAD (iOS). Must suspend until the
     * SDK reports completion or [dev.avinya.ads.internal.InitializationTimeouts.nativeInitialize]
     * elapses; a timeout must surface as a thrown failure, not a `CancellationException` (see
     * `awaitNativeCallback`), so a retry after a timeout is possible.
     *
     * [markHandoff] MUST be invoked exactly once, immediately before the FIRST irreversible touch of
     * the process-global native SDK, and MUST NOT be invoked on any path that fails before that point.
     * Everything an implementation does before that call is still undoable — building a config object,
     * mapping a request configuration — and pinning ownership there would turn a fixable
     * misconfiguration into a permanent, non-retryable conflict. Everything after it is owned by the
     * process whether or not the callback ever arrives.
     */
    protected abstract suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
        markHandoff: suspend () -> Unit,
    )

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

    /**
     * Dispatcher [nativeInitializationScope] runs on. Android uses `Dispatchers.Main.immediate`;
     * iOS uses plain `Dispatchers.Main`. This is an unexplained platform difference, not a
     * deliberate one — nothing in this repo documents why they diverge. Do not "fix" it by
     * unifying the two without first finding out why.
     */
    protected abstract val nativeInitializationDispatcher: CoroutineDispatcher

    // Runs on a detached SupervisorJob scope, not on any individual caller's coroutine, so
    // cancelling one initialize() caller can never interrupt native initialization — every
    // caller (leader and followers) only awaits its result. `by lazy`: [nativeInitializationDispatcher]
    // is an abstract member a subclass overrides, and reading an overridden member from a base
    // class's own eager property initializer would observe it before the subclass's
    // initializer runs. Deferring construction to first use (always well after the whole object
    // is constructed) sidesteps that entirely.
    private val nativeInitializationScope by lazy { CoroutineScope(SupervisorJob() + nativeInitializationDispatcher) }
    private var pendingInitialization: InitializationAttempt? = null
    private var nativeInitialization: NativeInitialization? = null
    private var nativeInitializationGeneration = 0L

    // Live admission state. Recomputed whenever consent mode or canRequestAds changes, so a
    // revocation through the privacy options form immediately closes the gate. Replaces the
    // previous sticky consentGateSatisfied latch.
    private val _admission = MutableStateFlow(AdRequestAdmission.NotGathered)

    // Own scope, deliberately NOT nativeInitializationScope, so the collector's lifetime is
    // independent of initialization. Main.immediate matches the dispatcher every GMA/UMP call
    // already uses, and is identical on both platforms (unlike nativeInitializationDispatcher
    // above).
    private val admissionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Starts the consent -> admission collector. Each platform subclass MUST call this from its
     * own `init {}` block, after its own `consent` property is constructed — NOT from this base
     * class's construction, because `consent` is an abstract member the subclass overrides with
     * an eagerly-constructed property, and a base class's own init-time code runs strictly
     * before a derived class's property initializers. Reading `consent` here during base
     * construction would observe it before it exists.
     */
    protected fun startAdmissionTracking() {
        admissionScope.launch {
            consent.canRequestAds.collectLatest { canRequest ->
                val next = consentSession.admission(canRequest)
                val previous = _admission.value
                if (previous == next) return@collectLatest
                _admission.value = next
                if (previous == AdRequestAdmission.Allowed && next == AdRequestAdmission.Revoked) {
                    AdLogger.w("$platformTag consent revoked. Closing ad request gate and purging cached inventory.")
                    purgeOnRevocation()
                }
            }
        }
    }

    @Volatile
    private var appliedConfigIdentity: AdInitializationConfigIdentity? = null
    /**
     * The identity handed to the process-global native SDK, pinned BEFORE the call and never
     * cleared.
     *
     * Distinct from [appliedConfigIdentity], which is committed only once native initialization
     * actually succeeds. The gap between the two is the whole point: `initializeMobileAdsNative`
     * bounds how long the wrapper waits for GMA's callback, and a
     * `NativeCallbackTimeoutException` means "the SDK accepted the call and never answered" — the
     * handoff happened, only the confirmation is missing. Committing ownership only on success
     * would let a retry with a DIFFERENT AdConfig sail past [appliedOutcome] and eventually
     * publish Ready while the native singleton still owns the first configuration.
     *
     * A timeout stops one waiter. It cannot undo an irreversible native handoff.
     */
    @Volatile
    private var handedOffConfigIdentity: AdInitializationConfigIdentity? = null
    @Volatile
    private var appliedTerminalStatus: AdManagerStatus? = null

    /**
     * The identity actually applied to the native SDK, or null before the first successful
     * `initialize()`. Exposed so a platform's own per-presentation audio controller can read
     * the applied [GlobalRequestConfiguration] without this base class needing to know that
     * controller exists (only Android has one today).
     */
    protected fun appliedConfigIdentitySnapshot(): AdInitializationConfigIdentity? = appliedConfigIdentity

    /** The consent mode to resume `initialize()` with after `showPrivacyOptions()` grants consent. */
    protected fun privacyOptionsResumeMode(): ConsentMode? = consentSession.modeForPrivacyOptionsResume()

    override suspend fun initialize(
        config: AdConfig,
        consentMode: ConsentMode
    ): AdManagerStatus {
        // A re-entrant call from inside an initialization hook cannot be admitted: the attempt
        // it would join cannot complete until this hook returns, and nothing in that path has a
        // timeout. Answer with the status the manager holds right now instead of deadlocking.
        // Both fields are @Volatile, so this takes no mutex — a hook may be running while the
        // operation still holds mobileAdsInitializationMutex.
        if (currentCoroutineContext()[InitializationHookMarker.Key] != null) {
            val current = appliedTerminalStatus ?: _status.value
            AdLogger.w(
                "$platformTag initialize() was called from inside an AdInitializationHook. " +
                    "Returning the current status ($current) instead of waiting for the " +
                    "initialization that is running this hook. Remove the nested call."
            )
            return current
        }
        val ownedConfig = config.ownedSnapshot()
        val requestedIdentity = ownedConfig.initializationIdentity(appId(ownedConfig))
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
                refusedByNativeHandoff(requestedIdentity)?.let { return it }
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
            } catch (attemptCancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                // An active follower must not inherit the leader's cancellation.
                continue
            }
            // Reconcile the FULL configuration before returning, equivalent attempt or not.
            // `equivalentAttempt` compares the native identity and the consent mode, and the
            // native ad memory policy is deliberately not part of that identity — so a follower
            // that asked for a different policy used to be told Ready while the leader's policy
            // was the one actually installed. The identical call made after initialization
            // finished returned INITIALIZATION_CONFLICT. Same request, different answer,
            // decided by timing. appliedOutcome() is the one place that check lives.
            if (result == AdManagerStatus.Ready) {
                appliedOutcome(requestedIdentity, ownedConfig.nativeAdMemoryPolicy)?.let { return it }
            }
            if (equivalentAttempt) return result
            // A distinct request waited for the process-wide slot but the leader
            // did not initialize GMA. Register a new attempt for its own semantics.
        }
    }

    private suspend fun runInitializationAttempt(
        attempt: InitializationAttempt,
        config: AdConfig,
        consentMode: ConsentMode
    ): AdManagerStatus {
        AdLogger.i("$platformTag initialize requested. consentMode=$consentMode")
        config.testModeWarningOrNull()?.let(AdLogger::w)
        // Checked before consent, not inside initializeMobileAds: on Android the value this
        // reads is what UMP (not GMA) resolves its identity from, so a mismatch is actionable
        // context for the consent gathering call right below, not just for GMA's own
        // initialization later. The check is now enforcement, not just context -- fatal
        // misconfigurations are rejected before consent or native SDK calls. Checked on every
        // initialize() attempt, not cached, matching testModeWarningOrNull's own cadence just above.
        when (
            val verdict = appIdVerdict(
                configuredAppId = appId(config),
                declared = declaredAppId(),
                declaredAppIdSource = declaredAppIdSource,
                declaredAppIdConsumerDescription = declaredAppIdConsumerDescription,
                requiredByPlatformSdk = declaredAppIdRequiredByPlatformSdk,
                policy = AdAppIdVerification.policy,
            )
        ) {
            AppIdVerdict.Ok -> Unit
            is AppIdVerdict.Warn -> AdLogger.w(verdict.message)
            is AppIdVerdict.Fail -> {
                AdLogger.e(verdict.message)
                // Stopped BEFORE consent and BEFORE any native call: a deterministic
                // configuration invalidity must not become an opaque native failure, and
                // gathering consent against the wrong application identity is worse than
                // gathering none.
                val result = AdManagerStatus.Failed(
                    error = appIdPreflightError(verdict.message),
                    retryable = false,
                )
                _status.value = result
                completeAttempt(attempt, result)
                return result
            }
        }

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

            AdLogger.i("$platformTag consent gate complete. canRequestAds=$effectiveCanRequest mode=$consentMode")
            if (!effectiveCanRequest) {
                AdLogger.w("$platformTag initialize deferred because consent does not allow ad requests yet.")
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
            configuredNativePolicy = configuredNativePolicyOrNull(),
            requestedNativePolicy = requestedNativeAdMemoryPolicy,
            appliedTerminalStatus = appliedTerminalStatus,
            currentStatus = _status.value,
        )
        when (decision) {
            is AppliedConfigurationDecision.NotApplied -> null
            is AppliedConfigurationDecision.Accepted ->
                decision.publish?.let(::publishAppliedTerminalLocked)
            is AppliedConfigurationDecision.Conflict -> {
                AdLogger.w("$platformTag MobileAds configuration conflict. ${decision.reason}")
                // Publish the TRUE status, return the rejection. Publishing the rejection would
                // make adRequestBlockedError() block every ad request process-wide, breaking the
                // caller whose configuration was actually accepted.
                publishAppliedTerminalLocked(decision.publish)
                decision.rejection
            }
        }
    }

    private suspend fun refusedByNativeHandoff(
        requestedIdentity: AdInitializationConfigIdentity,
    ): AdManagerStatus? = mobileAdsInitializationMutex.withLock {
        when (val decision = nativeHandoffDecision(handedOffConfigIdentity, requestedIdentity)) {
            NativeHandoffDecision.Proceed -> null
            is NativeHandoffDecision.Refuse -> {
                AdLogger.w("$platformTag MobileAds handoff conflict. ${decision.reason}")
                // The rejection is deliberately NOT published -- see NativeHandoffDecision.Refuse.
                decision.rejection
            }
        }
    }

    /** The platform's own `nativeManager.configuredPolicyOrNull()` — see [onNativeConsentRevoked]. */
    protected abstract fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy?

    private suspend fun appliedTerminalStatus(): AdManagerStatus? =
        mobileAdsInitializationMutex.withLock { appliedTerminalStatus }

    private suspend fun awaitAbandonedNativeInitialization(): NativeInitializationResult? {
        val operation = mobileAdsInitializationMutex.withLock { nativeInitialization } ?: return null
        return operation.completion.await()
    }

    private suspend fun initializeMobileAds(config: AdConfig): AdManagerStatus {
        val requestedIdentity = config.initializationIdentity(appId(config))
        appliedOutcome(requestedIdentity, config.nativeAdMemoryPolicy)?.let { return it }
        // The primary check is before consent in initialize(); this backstop covers a follower
        // that loops after a leader's attempt settled and re-enters with its own identity.
        refusedByNativeHandoff(requestedIdentity)?.let { return it }

        var operation = mobileAdsInitializationMutex.withLock { nativeInitialization }
        if (operation == null) {
            withContext(Dispatchers.Main.immediate) {
                config.dispatchInitializationHooks(AdInitializationPhase.BeforeMobileAdsInitialize)
            }
            operation = startNativeInitialization(config, requestedIdentity)
        }

        // Never regress a terminal status that has already been published. The detached native
        // operation now publishes Ready as soon as GMA accepts, so a caller arriving after that
        // (a follower, or a retry) would otherwise flip a live Ready manager back to
        // Initializing and close the request gate under ads that are already loading. Kept
        // rather than deleted: after an abandoned FAILED native attempt this is still what
        // moves the manager from Failed back to Initializing for the retry.
        val previousStatus = _status.value
        mobileAdsInitializationMutex.withLock {
            if (appliedTerminalStatus != AdManagerStatus.Ready) _status.value = AdManagerStatus.Initializing
        }
        return try {
            when (val nativeResult = operation.completion.await()) {
                is NativeInitializationResult.Failed -> return initializationFailed(nativeResult.failure)
                NativeInitializationResult.Applied -> Unit
            }
            AdLogger.i("$platformTag MobileAds initialization succeeded.")
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
        // MUST run on nativeInitializationScope (a detached SupervisorJob scope), not on any
        // individual caller's coroutine: cancelling one initialize() caller must never interrupt
        // this operation — every caller (leader and followers) only awaits its result. That
        // detachment is what guarantees AfterMobileAdsInitialize fires exactly once whenever
        // native init succeeds, regardless of which caller's coroutine gets cancelled.
        //
        // Native acceptance MUST be committed BEFORE the hook runs. Commit it after, and a
        // throwing publisher hook leaves appliedConfigIdentity null while GMA is already
        // initialized, so a retry with a different app ID sails past appliedOutcome() and
        // tries to reconfigure an immutable process singleton.
        //
        // Ready is now PUBLISHED before the hook too, not merely recorded. An After hook is
        // documented as running after initialization completes, and publishers reasonably
        // await Ready or load an ad from one; with publication deferred until after the hook,
        // both deadlocked. The consequence to know: an ad request can begin while an After
        // hook is still running. Work that must precede the first request belongs in
        // BeforeMobileAdsInitialize, which is what mediation privacy flags already use.
        val completion = nativeInitializationScope.async(start = CoroutineStart.LAZY) {
            val result = try {
                // The platform implementation decides when the mark is taken (via markHandoff):
                // iOS mutates GADMobileAds.sharedInstance.requestConfiguration before start(), while
                // Android builds its InitializationConfig first and only marks immediately before
                // MobileAds.initialize. From that mark on, this identity is what the process owns.
                initializeMobileAdsNative(config, requestedIdentity) {
                    mobileAdsInitializationMutex.withLock { handedOffConfigIdentity = requestedIdentity }
                }
                // Captured after the native call returns so adapter statuses are populated, and
                // before the After hook so a publisher hook can read them.
                captureDiagnosticsSnapshotOnMain()
                mobileAdsInitializationMutex.withLock {
                    // Native GMA is initialized at this point and can never be reconfigured, so
                    // the identity commit must be unconditional.
                    appliedConfigIdentity = requestedIdentity
                    appliedTerminalStatus = AdManagerStatus.Ready
                    // Publish Ready BEFORE configuring native ads, not after.
                    //
                    // configureNativeAdsAfterAcceptedInitialization materialises dormant native
                    // sessions and replays their windows, which immediately schedules demand on
                    // the coordinator's own dispatcher. That demand passes through the native
                    // request gate, which requires status == Ready. Published afterwards, the
                    // gate refused, the refusal was recorded as a NON-retryable slot error, and
                    // reconcileDemands() deliberately skips a slot with lastError set — so a
                    // feed or inline slot created while initialize() was still running stayed
                    // permanently Failed even though initialization succeeded, until the slot
                    // left the viewport and came back.
                    //
                    // Still inside this lock, so appliedOutcome()'s memory-policy check stays
                    // serialized against the configure call it describes.
                    publishAppliedTerminalLocked(AdManagerStatus.Ready)
                    // configureNativeAds can throw -- NativeAdManagerImpl.configure has a
                    // check(existing == null) -- which is the same trap as a throwing hook, one
                    // step earlier.
                    try {
                        configureNativeAdsAfterAcceptedInitialization(config)
                    } catch (t: Throwable) {
                        AdLogger.e(
                            "Failed to bind the native ad memory policy after $platformTag MobileAds " +
                                "initialization. GMA stays initialized and Ready; native ad " +
                                "capacity keeps its previously configured policy.",
                            t
                        )
                    }
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
                    // Terminal publication is owned by this operation because no waiter is
                    // guaranteed to exist. A caller cancelled after handoff leaves nobody to
                    // publish, and the manager would otherwise sit at its pre-attempt status --
                    // blocking every ad request through adRequestBlockedError() -- while the
                    // process-global native SDK is already initialized (or already, terminally,
                    // failed). Both outcomes publish; only the guard differs.
                    when (result) {
                        // The success path assigned appliedConfigIdentity just above, so this
                        // guard confirms THIS operation is the one that committed.
                        is NativeInitializationResult.Applied ->
                            if (appliedConfigIdentity == requestedIdentity) {
                                appliedTerminalStatus?.let { publishAppliedTerminalLocked(it) }
                            }
                        // A failure never assigns appliedConfigIdentity, so it must not be
                        // required to match: requiring it made this branch unreachable for a
                        // genuine native failure. What must hold is that no OTHER configuration
                        // owns the process -- publishing this failure over a configuration that
                        // was actually applied would take down ad serving for the caller whose
                        // configuration succeeded.
                        is NativeInitializationResult.Failed ->
                            if (appliedConfigIdentity == null || appliedConfigIdentity == requestedIdentity) {
                                val failed = failedInitializationStatus(result.failure)
                                appliedTerminalStatus = failed
                                publishAppliedTerminalLocked(failed)
                            }
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
        AdLogger.e("$platformTag MobileAds initialization failed.", failure)
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
        AdLogger.e("$platformTag MobileAds initialization failed.", failure)
        return failedInitializationStatus(failure).also { failed -> _status.value = failed }
    }

    private fun failedInitializationStatus(failure: Throwable): AdManagerStatus.Failed =
        AdManagerStatus.Failed(
            error = AdError.message(failure.message ?: "Google Mobile Ads initialization failed."),
            retryable = true
        )

    protected fun adRequestBlockedError(): AdError? = when {
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
     * runs; view-state coherence there is owned by BannerCore/NativeAdCoordinatorCore
     * and is not addressed here.
     */
    private fun purgeOnRevocation() {
        val (bannersSnapshot, slotsSnapshot) = registryLock.withLock {
            Pair(banners.values.toList(), slots.values.toList())
        }
        bannersSnapshot.forEach { it.clear() }
        slotsSnapshot.forEach { it.clear() }
        onNativeConsentRevoked()
        AdLogger.i(
            "$platformTag revocation purge complete. banners=${bannersSnapshot.size} " +
                "fullScreen=${slotsSnapshot.size} nativeSessions=${nativeAds.state.value.activeSessions}"
        )
    }

    /** Writes [configuration] onto the platform SDK. Called on Main, under the init mutex. */
    internal abstract suspend fun applyGlobalRequestConfigurationNative(
        configuration: GlobalRequestConfiguration,
    )

    /**
     * Shared implementation of [dev.avinya.ads.updateGlobalRequestConfiguration].
     *
     * Under `mobileAdsInitializationMutex` so this cannot interleave with an initialization
     * attempt: the applied identity is what `appliedOutcome()` compares a later `initialize()`
     * against, and a half-updated identity would make an equivalent call look like a conflict.
     */
    final override suspend fun updateGlobalRequestConfiguration(
        configuration: GlobalRequestConfiguration,
    ): RequestConfigurationUpdateResult {
        val owned = configuration.ownedSnapshot()
        val outcome = mobileAdsInitializationMutex.withLock {
            val applied = appliedConfigIdentity
                ?: return@withLock RequestConfigurationUpdateResult.NotInitialized
            val previous = applied.globalRequestConfiguration
            if (previous == owned) {
                // Nothing to do, and nothing to invalidate. Reported as Applied because the
                // requested configuration IS in force, which is what the caller asked about.
                return@withLock RequestConfigurationUpdateResult.Applied(invalidatedCachedAds = false)
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    applyGlobalRequestConfigurationNative(owned)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AdLogger.e("$platformTag failed to update the global request configuration.", failure)
                return@withLock RequestConfigurationUpdateResult.Failed(
                    AdError.message(failure.message ?: "Failed to update the request configuration.")
                )
            }
            // Committed only after the platform accepted it, so a failed update leaves the
            // recorded identity describing what the SDK actually holds. A later initialize()
            // with the SAME new configuration is then equivalent rather than a conflict, and
            // Android's audio-restore baseline follows the update too.
            appliedConfigIdentity = applied.copy(globalRequestConfiguration = owned)
            RequestConfigurationUpdateResult.Applied(
                invalidatedCachedAds = previous.affectsServedAds(owned),
            )
        }
        // Outside the mutex: clearing a slot can destroy ads and take per-slot locks, which
        // must not happen while the initialization mutex is held.
        if (outcome is RequestConfigurationUpdateResult.Applied && outcome.invalidatedCachedAds) {
            AdLogger.i(
                "$platformTag request configuration changed in a way that affects ad serving; " +
                    "dropping inventory loaded under the previous policy."
            )
            purgeOnRevocation()
        }
        return outcome
    }
}
