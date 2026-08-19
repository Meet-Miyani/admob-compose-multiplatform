package dev.avinya.ads.nativead

import dev.avinya.ads.AdError
import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdUnitIds
import dev.avinya.ads.internal.NativeMemoryPressure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IosNativeAdPlatformTest {
    private fun placement(batching: NativeAdBatching) = AdPlacement("ios-$batching", AdFormat.Native, AdUnitIds("a", "i"), nativeOptions = NativeAdOptions(batching = batching))
    @Test fun `sequential count three creates three loaders without multiple ads option`() = runTest { val f=Fake<String>(); val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.Sequential),3,1); repeat(3){f.ad("$it");f.finish()}; assertEquals(listOf(false,false,false),f.multiple); assertEquals(3,d.await().ads.size) }
    @Test fun `sequential partial success retains prior ads and terminal error`() = runTest { val f=Fake<String>();val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.Sequential),2,1);f.ad("a");f.finish();f.error(AdError.message("x"));f.finish();assertEquals(listOf("a"),d.await().ads);assertEquals("x",d.await().unfilledError?.message) }
    @Test fun `google only accepts exact counts one through five and rejects larger before GMA`() = runTest { (1..5).forEach{n->val f=Fake<String>();val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.GoogleOnly),n,1);assertEquals(listOf(true),f.multiple);f.finish();d.await()};val f=Fake<String>();assertFailsWith<IllegalArgumentException>{IosNativeLoadMachine(f).load(placement(NativeAdBatching.GoogleOnly),6,1)};assertEquals(emptyList(),f.multiple) }
    @Test fun `active registry strongly retains loader and delegate until finish callback`() = runTest { val f=Fake<String>();val m=IosNativeLoadMachine(f);val d=m.load(placement(NativeAdBatching.GoogleOnly),1,1);assertEquals(1,m.activeLoadCount);f.finish();d.await();assertEquals(0,m.activeLoadCount) }
    @Test fun `cancellation settles coroutine but retains invalidated delegate until finish`() = runTest { val f=Fake<String>();val m=IosNativeLoadMachine(f);val d=m.load(placement(NativeAdBatching.GoogleOnly),1,1);d.cancel();assertEquals(1,m.activeLoadCount);f.finish();assertEquals(0,m.activeLoadCount) }
    @Test fun `late invalidated delegate tears down every arriving ad`() = runTest { val f=Fake<String>();val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.GoogleOnly),1,1);d.cancel();f.ad("late");assertEquals(listOf("late"),f.destroyed);f.finish() }
    @Test fun `finish and cancellation race resumes continuation once`() = runTest { val f=Fake<String>();val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.GoogleOnly),1,1);f.finish();d.cancel();d.await();assertEquals(1,f.finishes) }
    @Test fun `metadata is snapshotted on Main before callback completion`() = runTest { val f=Fake<String>();val d=IosNativeLoadMachine(f).load(placement(NativeAdBatching.GoogleOnly),1,1);f.ad("main-snapshot");f.finish();assertEquals("main-snapshot",d.await().ads.single()) }
    @Test fun `load machine delegates teardown to the platform facade`() = runTest { val f=Fake<String>();val m=IosNativeLoadMachine(f);val d=m.load(placement(NativeAdBatching.GoogleOnly),1,1);f.ad("a");f.finish();d.await();m.destroy("a");assertEquals(listOf("a"),f.destroyed) }
    @Test fun `zero ad result is failure while partial result remains success`() {
        assertIs<AdAttemptResult.Failure>(IosNativeLoadResult<String>(emptyList(), AdError.message("empty")).toAttemptResult())
        val partial = assertIs<AdAttemptResult.Success<*>>(IosNativeLoadResult(listOf("a"), AdError.message("partial")).toAttemptResult())
        assertEquals(1, (partial.value as dev.avinya.ads.internal.NativeAdPlatformBatch<*>).ads.size)
    }
    @Test fun `destroy gate runs teardown once without retaining an ad registry`() { val gate=IosNativeDestroyGate();var calls=0;gate.destroyOnce{calls++};gate.destroyOnce{calls++};assertEquals(1,calls) }
    @Test fun `memory warning emits critical and observer removal is idempotent`() { val f=Warnings();val got=mutableListOf<NativeMemoryPressure>();val s=IosNativeMemorySignal(f){got+=it};f.fire();s.close();s.close();assertEquals(listOf(NativeMemoryPressure.Critical),got);assertEquals(1,f.removed) }

    // --- NATIVE-03: a facade that throws synchronously ---------------------------------
    // facade.start() can throw while constructing GADAdLoader, resolving the top view
    // controller, or when an ObjC exception surfaces into Kotlin. The flight is already in
    // `active` and may already hold ads accepted via onAd.

    @Test
    fun `a synchronous start failure releases the load slot`() = runTest {
        val f = ThrowingFake<String>(throwOnStart = IllegalStateException("no root view controller"))
        val m = IosNativeLoadMachine(f)

        assertFailsWith<IllegalStateException> {
            m.load(placement(NativeAdBatching.GoogleOnly), 1, 1)
        }
        // Leaked, this permanently consumed native ad capacity for the whole process.
        assertEquals(0, m.activeLoadCount)
    }

    @Test
    fun `a start failure after an accepted ad destroys that ad exactly once`() = runTest {
        val f = ThrowingFake<String>(
            throwOnStart = IllegalStateException("blew up after installing callbacks"),
            emitBeforeThrow = "accepted",
        )
        val m = IosNativeLoadMachine(f)

        assertFailsWith<IllegalStateException> {
            m.load(placement(NativeAdBatching.GoogleOnly), 1, 1)
        }
        assertEquals(0, m.activeLoadCount)
        // Without terminal cleanup the GADNativeAd stayed retained with no teardown path.
        assertEquals(listOf("accepted"), f.destroyed)
    }

    @Test
    fun `a start failure does not disturb an unrelated in-flight load`() = runTest {
        val healthy = Fake<String>()
        val healthyMachine = IosNativeLoadMachine(healthy)
        val d = healthyMachine.load(placement(NativeAdBatching.GoogleOnly), 1, 1)
        assertEquals(1, healthyMachine.activeLoadCount)

        val broken = IosNativeLoadMachine(ThrowingFake<String>(throwOnStart = IllegalStateException("x")))
        assertFailsWith<IllegalStateException> { broken.load(placement(NativeAdBatching.GoogleOnly), 1, 1) }

        assertEquals(1, healthyMachine.activeLoadCount, "the healthy flight must be untouched")
        healthy.ad("ok"); healthy.finish()
        assertEquals(listOf("ok"), d.await().ads)
    }

    // --- NATIVE-01: the placement registry --------------------------------------------

    @Test
    fun `the placement registry attributes each ad to its own placement`() {
        val registry = NativePlacementRegistry<String>()
        registry.register(listOf("a1", "a2"), "feed")
        registry.register(listOf("b1"), "detail")

        assertEquals("feed", registry.placementOf("a1"))
        assertEquals("feed", registry.placementOf("a2"))
        assertEquals("detail", registry.placementOf("b1"))
        assertEquals(3, registry.size)
    }

    @Test
    fun `removing one ad leaves its siblings attributed`() {
        val registry = NativePlacementRegistry<String>()
        registry.register(listOf("a1", "a2", "a3"), "feed")

        registry.remove("a2")

        // The race this guards produced exactly this symptom: an event bound with an empty or
        // wrong placement id because a concurrent destroy corrupted the map.
        assertEquals("feed", registry.placementOf("a1"))
        assertEquals("feed", registry.placementOf("a3"))
        assertEquals(null, registry.placementOf("a2"))
        assertEquals(2, registry.size)
    }

    @Test
    fun `removing an unknown ad is a no-op and repeated removal is idempotent`() {
        val registry = NativePlacementRegistry<String>()
        registry.register(listOf("a1"), "feed")

        registry.remove("never-registered")
        registry.remove("a1")
        registry.remove("a1")

        assertEquals(0, registry.size)
        assertEquals(null, registry.placementOf("a1"))
    }

    @Test
    fun `re-registering an ad moves its attribution`() {
        val registry = NativePlacementRegistry<String>()
        registry.register(listOf("a1"), "feed")
        registry.register(listOf("a1"), "detail")

        assertEquals("detail", registry.placementOf("a1"))
        assertEquals(1, registry.size)
    }
}

private class Fake<A:Any>:IosNativeAdLoaderFacade<A>{data class C<A:Any>(val ad:(A)->Unit,val err:(AdError)->Unit,val end:()->Unit);val calls=mutableListOf<C<A>>();val multiple=mutableListOf<Boolean>();val destroyed=mutableListOf<A>();var finishes=0;override fun start(p:AdPlacement,count:Int,multiple:Boolean,onAd:(A)->Unit,onError:(AdError)->Unit,onFinish:()->Unit){this.multiple+=multiple;calls+=C(onAd,onError,onFinish)};fun ad(a:A){calls.last().ad(a)};fun error(e:AdError){calls.last().err(e)};fun finish(){finishes++;calls.removeLast().end()};override fun destroy(ad:A){destroyed+=ad}}
private class Warnings:IosMemoryWarnings{var c:(()->Unit)?=null;var removed=0;override fun add(callback:()->Unit):Any{c=callback;return this};override fun remove(token:Any){removed++;c=null};fun fire(){c?.invoke()}}

private class ThrowingFake<A : Any>(
    private val throwOnStart: Throwable,
    private val emitBeforeThrow: A? = null,
) : IosNativeAdLoaderFacade<A> {
    val destroyed = mutableListOf<A>()
    override fun start(p: AdPlacement, count: Int, multiple: Boolean, onAd: (A) -> Unit, onError: (AdError) -> Unit, onFinish: () -> Unit) {
        // Models a facade that installs its callbacks, accepts an ad, and only then fails.
        emitBeforeThrow?.let(onAd)
        throw throwOnStart
    }
    override fun destroy(ad: A) { destroyed += ad }
}
