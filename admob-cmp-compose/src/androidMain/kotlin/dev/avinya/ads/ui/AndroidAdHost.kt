package dev.avinya.ads.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.viewinterop.NoOpUpdate

/**
 * The only way the SDK embeds an Android ad view in Compose. It is `AndroidView` plus one guarantee:
 * nothing inside the host can hold Android input focus.
 *
 * Compose removes an `AndroidView` in the middle of applying a composition, or of measuring when a lazy
 * layout re-subcomposes an item. If the removed subtree holds input focus, `ViewGroup.removeViewInternal`
 * immediately calls `rootViewRequestFocus()`, which lands in `AndroidComposeView.requestFocus`. That runs a
 * synchronous Compose focus search, and inside a lazy list or grid the search lays out beyond-bounds items
 * while Compose is still mid-apply or mid-measure. Compose aborts with "pending composition has not been
 * applied" or "performMeasureAndLayout called during measure layout". The removal can come from the SDK (an
 * ad refresh, a new `AdLayout` identity) or from the host app (hiding the slot, navigating away), so it
 * cannot be sequenced around.
 *
 * Focus cannot be handed back safely at removal time either: `View.clearFocus()` re-requests root focus
 * whenever the window is not in touch mode, which is the same re-entry. So focus is never allowed in.
 *
 * The block lives on [AdHostFrame], a view the SDK owns, rather than on the ad view the factory returns.
 * For native ads that view is GMA's `NativeAdView`; a guarantee this crash depends on must not rest on a
 * closed-source view that a GMA update could reconfigure. `View.requestFocus` refuses while any ancestor
 * blocks descendant focus, so whatever the ad view or GMA does to its own focusability, and whatever views
 * are added later, nothing below the frame can take focus.
 *
 * What this costs: keyboard and D-pad traversal skip the ad, and an HTML banner creative containing a text
 * field cannot raise the keyboard, because its WebView cannot take focus. Touch clicks do not use input focus
 * and TalkBack moves accessibility focus, which is separate; both are unaffected.
 *
 * Upstream Compose UI 1.12.1 already clears focus before this removal, but only behind
 * `ComposeUiFlags.isViewFocusFixEnabled`, which is off by default and app-global, so it is not the SDK's to
 * flip. `AndroidAdHostFocusTest` keeps a control that fails once Compose no longer crashes.
 */
@Composable
internal fun <T : View> AndroidAdHost(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    onRelease: ((T) -> Unit)? = null,
    update: ((T) -> Unit)? = null,
) {
    AndroidView(
        factory = { context -> AdHostFrame(context, factory(context)) },
        modifier = modifier,
        onRelease = onRelease?.let { release -> { host: AdHostFrame<T> -> release(host.adView) } } ?: NoOpUpdate,
        update = update?.let { apply -> { host: AdHostFrame<T> -> apply(host.adView) } } ?: NoOpUpdate,
    )
}

/**
 * An SDK-owned, layout-transparent parent for one ad view that blocks input focus for everything inside it.
 *
 * It must not change how the ad view is measured, laid out or drawn, so it forwards exactly what Compose's
 * `AndroidViewHolder` gives its direct child: the holder's measure specs, bounds at the origin, zero size for
 * a `GONE` child, and no clipping of its own. The holder derives those specs from its child's layout params,
 * so the frame carries the ad view's params when it has any; otherwise both get the holder's defaults.
 */
internal class AdHostFrame<T : View>(context: Context, val adView: T) : ViewGroup(context) {

    init {
        adView.layoutParams?.let { layoutParams = LayoutParams(it) }
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false
        clipChildren = false
        clipToPadding = false
        addView(adView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (adView.visibility == GONE) {
            setMeasuredDimension(0, 0)
            return
        }
        adView.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(adView.measuredWidth, adView.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        adView.layout(0, 0, r - l, b - t)
    }
}
