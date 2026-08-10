package dev.avinya.admob.showcase.di

import kotlin.concurrent.AtomicReference

private val iosAppGraphInstance = AtomicReference<AppGraph?>(null)

internal actual fun getOrCreateAppGraph(create: () -> AppGraph): AppGraph {
    iosAppGraphInstance.value?.let { return it }
    val newGraph = create()
    if (iosAppGraphInstance.compareAndSet(null, newGraph)) {
        return newGraph
    }
    return iosAppGraphInstance.value!!
}

internal actual fun resetAppGraphForTesting() {
    iosAppGraphInstance.value = null
}
