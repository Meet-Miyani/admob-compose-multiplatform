package dev.avinya.ads

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidGoogleAdManagerNativePolicyTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `native facade remains deferred then binds the accepted non-default policy once`() {
        val manager = AndroidGoogleAdManager(mock(Context::class.java)) { null }
        val config = AdConfig(
            androidAppId = "android-app",
            iosAppId = "ios-app",
            nativeAdMemoryPolicy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 3),
        )

        assertEquals(0, manager.nativeAds.state.value.loadedAds)
        assertEquals(NativeAdMemoryPolicy(), manager.nativeAds.policy)

        manager.configureNativeAdsAfterAcceptedInitialization(config)
        manager.configureNativeAdsAfterAcceptedInitialization(config)

        assertEquals(config.nativeAdMemoryPolicy, manager.nativeAds.policy)
    }

    @Test
    fun `declaredAppId reads the manifest APPLICATION_ID meta-data as Present`() {
        val context = mock(Context::class.java)
        val packageManager = mock(PackageManager::class.java)
        val metaDataBundle = mock(Bundle::class.java)
        val applicationInfo = ApplicationInfo().apply { metaData = metaDataBundle }
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(context.packageName).thenReturn("dev.avinya.showcase")
        `when`(packageManager.getApplicationInfo("dev.avinya.showcase", PackageManager.GET_META_DATA))
            .thenReturn(applicationInfo)
        `when`(metaDataBundle.getString("com.google.android.gms.ads.APPLICATION_ID"))
            .thenReturn("ca-app-pub-native-manifest-id")

        val manager = AndroidGoogleAdManager(context) { null }

        assertEquals(DeclaredAppId.Present("ca-app-pub-native-manifest-id"), manager.declaredAppId())
    }

    @Test
    fun `declaredAppId reports Missing when the meta-data bundle has no value for the key`() {
        val context = mock(Context::class.java)
        val packageManager = mock(PackageManager::class.java)
        val metaDataBundle = mock(Bundle::class.java)
        val applicationInfo = ApplicationInfo().apply { metaData = metaDataBundle }
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(context.packageName).thenReturn("dev.avinya.showcase")
        `when`(packageManager.getApplicationInfo("dev.avinya.showcase", PackageManager.GET_META_DATA))
            .thenReturn(applicationInfo)
        `when`(metaDataBundle.getString("com.google.android.gms.ads.APPLICATION_ID")).thenReturn(null)

        val manager = AndroidGoogleAdManager(context) { null }

        assertEquals(DeclaredAppId.Missing, manager.declaredAppId())
    }

    @Test
    fun `declaredAppId reports Missing when there is no meta-data block at all`() {
        val context = mock(Context::class.java)
        val packageManager = mock(PackageManager::class.java)
        val applicationInfo = ApplicationInfo() // metaData left null: no <meta-data> in the manifest
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(context.packageName).thenReturn("dev.avinya.showcase")
        `when`(packageManager.getApplicationInfo("dev.avinya.showcase", PackageManager.GET_META_DATA))
            .thenReturn(applicationInfo)

        val manager = AndroidGoogleAdManager(context) { null }

        assertEquals(DeclaredAppId.Missing, manager.declaredAppId())
    }

    @Test
    fun `declaredAppId reports Unknown instead of throwing when package manager state is unavailable`() {
        // A bare mock's packageManager is null, exercising the try/catch fallback in
        // AndroidGoogleAdManager.declaredAppId -- a read failure must degrade to Unknown (never
        // a warning), not crash initialize(), and must not be conflated with a confirmed-absent
        // Missing value.
        val context = mock(Context::class.java)
        val manager = AndroidGoogleAdManager(context) { null }

        assertEquals(DeclaredAppId.Unknown, manager.declaredAppId())
    }
}
