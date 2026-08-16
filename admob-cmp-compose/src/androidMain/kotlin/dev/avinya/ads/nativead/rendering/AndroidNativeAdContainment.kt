package dev.avinya.ads.nativead.rendering

internal data class NativeAdBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /**
     * @param tolerance slack, in pixels, before an asset counts as escaping.
     *
     * Exact integer containment made this gate unforgiving in a way iOS's equivalent (which allows
     * half a point) is not. Every dp in the layout is converted with `roundToInt`, so an asset
     * pinned flush to the root's edge can land a single pixel outside it — and because a failed
     * check skips `registerNativeAd` entirely, that one pixel meant an ad that rendered perfectly
     * and never recorded an impression or a click, reporting nothing but a log line.
     */
    fun contains(other: NativeAdBounds, tolerance: Int = DEFAULT_TOLERANCE_PX): Boolean =
        other.left >= left - tolerance &&
            other.top >= top - tolerance &&
            other.right <= right + tolerance &&
            other.bottom <= bottom + tolerance

    internal companion object {
        const val DEFAULT_TOLERANCE_PX: Int = 1
    }
}
