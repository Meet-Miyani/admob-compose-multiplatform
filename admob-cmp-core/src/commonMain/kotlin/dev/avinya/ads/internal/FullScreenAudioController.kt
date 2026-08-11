package dev.avinya.ads.internal

import dev.avinya.ads.FullScreenAdOptions

internal interface FullScreenAudioController {
    fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle?
}

internal fun interface AudioRestoreHandle {
    fun restore()
}
