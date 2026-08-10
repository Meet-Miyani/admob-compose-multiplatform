package dev.avinya.admob.showcase.di

import androidx.room.Room
import androidx.room.RoomDatabase
import dev.avinya.admob.showcase.data.db.ShowcaseDatabase
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AppGraphTest {

    @AfterTest
    fun tearDown() {
        AppGraph.resetForTesting()
    }

    @Test
    fun testAppGraphGetReturnsSingletonInstance() {
        val storage = FakePlatformStorage()
        val graph1 = AppGraph.get(storage)
        val graph2 = AppGraph.get(storage)
        assertSame(graph1, graph2, "AppGraph.get should return the exact same process-wide singleton instance")
    }

    @Test
    fun testAppGraphResetForTestingClearsSingleton() {
        val storage = FakePlatformStorage()
        val graph1 = AppGraph.get(storage)
        AppGraph.resetForTesting()
        val graph2 = AppGraph.get(storage)
        assertNotSame(graph1, graph2, "AppGraph.resetForTesting should allow creating a fresh instance")
    }

    private class FakePlatformStorage : PlatformStorage {
        override fun databaseBuilder(): RoomDatabase.Builder<ShowcaseDatabase> {
            return Room.databaseBuilder<ShowcaseDatabase>(name = "test_showcase_${Random.nextInt()}.db")
        }

        override fun dataStorePath(): String = "test_showcase_${Random.nextInt()}.preferences_pb"
    }
}
