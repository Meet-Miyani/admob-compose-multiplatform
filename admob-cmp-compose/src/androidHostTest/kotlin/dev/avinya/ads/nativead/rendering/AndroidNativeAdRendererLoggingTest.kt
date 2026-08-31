package dev.avinya.ads.nativead.rendering

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.DisplayMetrics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import dev.avinya.ads.AdLogSink
import dev.avinya.ads.AdLogger
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.AdTextStyle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AndroidNativeAdRendererLoggingTest {
    private val originalSink = AdLogger.sink

    @AfterTest
    fun tearDown() {
        AdLogger.sink = originalSink
    }

    @Test
    fun `render logging does not expose static layout text or the full identity`() {
        val messages = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> messages += message }
        val sensitiveStaticText = "account-owner-email@example.test"
        val composeFontFamily = FontFamily.SansSerif
        val layout = AdLayout(
            root = AdStaticText(
                text = sensitiveStaticText,
                style = AdTextStyle(fontFamily = AdFontFamily.FromCompose(composeFontFamily)),
            ),
        )
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(DisplayMetrics().apply { density = 1f })
        val nativeAdView = mock(NativeAdView::class.java)
        val renderer = AndroidNativeAdLayoutRenderer(
            context = context,
            nativeAd = mock(NativeAd::class.java),
            resolvedComposeFonts = ResolvedComposeFonts(
                mapOf(
                    ComposeFontRequest(composeFontFamily, FontWeight.Normal) to
                        mock(Typeface::class.java),
                ),
            ),
            existingRoot = nativeAdView,
        )

        renderer.render(layout)

        assertTrue(messages.none { sensitiveStaticText in it })
        assertTrue(messages.none { layout.identity in it })
    }
}
