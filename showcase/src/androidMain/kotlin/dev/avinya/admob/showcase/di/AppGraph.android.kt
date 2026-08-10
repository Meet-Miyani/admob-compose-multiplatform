package dev.avinya.admob.showcase.di

private var androidAppGraphInstance: AppGraph? = null

internal actual fun getOrCreateAppGraph(create: () -> AppGraph): AppGraph {
    return androidAppGraphInstance ?: synchronized(AppGraph::class.java) {
        androidAppGraphInstance ?: create().also { androidAppGraphInstance = it }
    }
}

internal actual fun resetAppGraphForTesting() {
    synchronized(AppGraph::class.java) {
        androidAppGraphInstance = null
    }
}
