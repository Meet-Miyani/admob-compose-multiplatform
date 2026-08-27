@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdManagerState
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSessionState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin public facade; coordinator cores remain the sole owners of records and work. */
internal class NativeAdManagerImpl<A : Any>(
    policy: NativeAdMemoryPolicy? = null,
    private val platform: NativeAdPlatform<A>,
    private val canRequestAds: () -> Boolean = { true },
    private val eventSink: (dev.avinya.ads.AdEvent) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NativeAdManager, NativeAdRenderLeaseProvider<A> {
    private val managerLock = FullScreenStateLock()
    private var configuredPolicy: NativeAdMemoryPolicy? = policy
    private var coordinator: NativeAdCoordinatorCore<A>? = policy?.let(::newCoordinator)
    private val dormantSessions = mutableMapOf<String, DormantSession>()

    override val policy: NativeAdMemoryPolicy
        get() = managerLock.withLock { configuredPolicy } ?: NativeAdMemoryPolicy()

    private val _state = MutableStateFlow(
        NativeAdManagerState(0, 0, 0, 0, policy?.hardLimit ?: NativeAdMemoryPolicy.DEFAULT_HARD_LIMIT),
    )
    override val state: StateFlow<NativeAdManagerState> = _state

    init {
        coordinator?.setStateListener(::publish)
    }

    /** Binds the coordinator once after the real platform manager accepts configuration. */
    fun configure(policy: NativeAdMemoryPolicy) {
        val pending = managerLock.withLock {
            val existing = configuredPolicy
            if (existing == policy) return@withLock emptyList<Pair<String, DormantSession>>()
            check(existing == null) {
                "Native ad memory policy is already configured and cannot be changed for this AdManager instance."
            }
            configuredPolicy = policy
            coordinator = newCoordinator(policy)
            dormantSessions.filterValues { !it.closed }.map { it.key to it.value }
                .also { dormantSessions.clear() }
        }
        val coordinator = coordinatorOrNull() ?: return
        pending.forEach { (key, dormant) -> materialise(coordinator, key, dormant) }
        publish()
    }

    private fun materialise(
        coordinator: NativeAdCoordinatorCore<A>,
        key: String,
        dormant: DormantSession,
    ) {
        val core = try {
            coordinator.session(key, dormant.handle?.policy ?: NativeAdSessionPolicy(), dormant.published)
        } catch (e: IllegalStateException) {
            AdLogger.w("Failed to materialise dormant native ad session '$key'", e)
            null
        } ?: return
        if (!core.publishesInto(dormant.published)) {
            AdLogger.w("Dormant native ad session '$key' state flow mismatch")
            return
        }
        val generation = checkNotNull(coordinator.sessionGeneration(key))
        val (initialWindow, shouldClose, shouldDeactivate) = managerLock.withLock {
            dormant.handle?.bind(generation)
            Triple(dormant.window, dormant.closed, dormant.deactivated)
        }
        initialWindow?.let { coordinator.updateWindow(key, generation, it) }
        if (shouldClose) {
            coordinator.closeSession(key, generation)
        } else if (shouldDeactivate) {
            coordinator.deactivateSession(key, generation)
        }
    }

    fun configuredPolicyOrNull(): NativeAdMemoryPolicy? = managerLock.withLock { configuredPolicy }

    private fun newCoordinator(policy: NativeAdMemoryPolicy): NativeAdCoordinatorCore<A> =
        NativeAdCoordinatorCore(
            memoryPolicy = policy,
            platform = platform,
            scope = scope,
            canRequestAds = canRequestAds,
            eventSink = eventSink,
        ).also { it.setStateListener(::publish) }

    private fun coordinator(): NativeAdCoordinatorCore<A> = managerLock.withLock {
        checkNotNull(coordinator) { "Native ads are unavailable until AdManager.initialize succeeds." }
    }
    private fun coordinatorOrNull(): NativeAdCoordinatorCore<A>? = managerLock.withLock { coordinator }

    override fun session(key: String, policy: NativeAdSessionPolicy): NativeAdSession {
        val dormant = managerLock.withLock {
            if (coordinator == null) dormantHandleLocked(key, policy) else null
        }
        if (dormant != null) return dormant
        val coordinator = coordinator()
        val core = coordinator.session(key, policy)
        val generation = checkNotNull(coordinator.sessionGeneration(key))
        publish()
        return Handle(key, policy, core.state, generation, dormant = null)
    }

    private fun dormantHandleLocked(key: String, policy: NativeAdSessionPolicy): NativeAdSession {
        require(key.isNotBlank()) { "session key must not be blank" }
        val existing = dormantSessions[key]
        if (existing != null) {
            val existingPolicy = existing.handle?.policy ?: policy
            if (existingPolicy != policy) {
                throw IllegalStateException(sessionPolicyMismatchMessage(key, existingPolicy))
            }
            return existing.handle!!
        }
        if (dormantSessions.size >= NativeAdMemoryPolicy.DEFAULT_MAX_SESSION_RECORDS) {
            throw IllegalStateException(
                sessionRegistryFullMessage(NativeAdMemoryPolicy.DEFAULT_MAX_SESSION_RECORDS, key)
            )
        }
        val published = MutableStateFlow(NativeAdSessionState(active = false, slots = emptyMap()))
        val dormant = DormantSession(published)
        val handle = Handle(key, policy, published.asStateFlow(), generation = null, dormant = dormant)
        dormant.handle = handle
        dormantSessions[key] = dormant
        return handle
    }

    override fun closeSession(key: String) {
        managerLock.withLock { dormantSessions.remove(key) }
        coordinatorOrNull()?.closeSession(key)
        publish()
    }

    override fun clear() { coordinatorOrNull()?.clear(); publish() }
    internal fun onConsentRevoked() { coordinatorOrNull()?.onConsentRevoked(); publish() }
    private fun publish() { _state.value = coordinatorOrNull()?.managerState() ?: _state.value }

    override fun acquireRender(slotKey: String, placement: AdPlacement, rendererId: String, session: NativeAdSession): NativeAdRenderRecord<A>? =
        (session as? NativeAdSessionRenderOwner<A>)?.takeIf { it.owner === this }?.let { handle ->
            val gen = managerLock.withLock { handle.generation } ?: return null
            coordinatorOrNull()?.acquireForRender(handle.sessionKey, gen, slotKey, placement, rendererId)
        }

    override fun releaseRender(slotKey: String, placement: AdPlacement, rendererId: String, recordId: NativeAdRecordId, session: NativeAdSession) {
        (session as? NativeAdSessionRenderOwner<A>)?.takeIf { it.owner === this }?.let { handle ->
            val gen = managerLock.withLock { handle.generation } ?: return
            coordinatorOrNull()?.releaseRenderer(handle.sessionKey, gen, slotKey, placement, recordId, rendererId)
            publish()
        }
    }

    private inner class DormantSession(val published: MutableStateFlow<NativeAdSessionState>) {
        var handle: Handle? = null
        var window: NativeAdWindow? = null
        var deactivated: Boolean = false
        var closed: Boolean = false
    }

    private inner class Handle(
        override val key: String,
        override val policy: NativeAdSessionPolicy,
        override val state: StateFlow<NativeAdSessionState>,
        generation: Long?,
        private val dormant: DormantSession?,
    ) : NativeAdSession, NativeAdSessionRenderOwner<A> {
        override val owner: NativeAdRenderLeaseProvider<A> get() = this@NativeAdManagerImpl
        override val sessionKey: String get() = key
        override var generation: Long? = generation
            private set

        fun bind(value: Long) {
            generation = value
        }

        private fun current(): Boolean {
            val gen = managerLock.withLock { generation } ?: return false
            return coordinatorOrNull()?.sessionGeneration(key) == gen
        }

        override fun updateWindow(window: NativeAdWindow) {
            if (dormant != null && managerLock.withLock { generation == null }) {
                managerLock.withLock { dormant.window = window }
                return
            }
            check(current()) { "Native ad session '$key' is no longer active." }
            val gen = managerLock.withLock { generation } ?: return
            coordinator().updateWindow(key, gen, window)
            publish()
        }

        override fun deactivate() {
            if (dormant != null && managerLock.withLock { generation == null }) {
                managerLock.withLock { dormant.deactivated = true }
                return
            }
            val gen = managerLock.withLock { generation }
            if (gen != null && current()) {
                coordinator().deactivateSession(key, gen)
                publish()
            }
        }

        override fun close() {
            if (dormant != null && managerLock.withLock { generation == null }) {
                managerLock.withLock {
                    dormant.closed = true
                    dormantSessions.remove(key)
                }
                return
            }
            val gen = managerLock.withLock { generation }
            if (gen != null && current()) {
                coordinator().closeSession(key, gen)
                publish()
            }
        }
    }
}

internal interface NativeAdRenderLeaseProvider<A : Any> {
    fun acquireRender(slotKey: String, placement: AdPlacement, rendererId: String, session: NativeAdSession): NativeAdRenderRecord<A>?
    fun releaseRender(slotKey: String, placement: AdPlacement, rendererId: String, recordId: NativeAdRecordId, session: NativeAdSession)
}

internal interface NativeAdSessionRenderOwner<A : Any> {
    val owner: NativeAdRenderLeaseProvider<A>
    val sessionKey: String
    val generation: Long?
}
