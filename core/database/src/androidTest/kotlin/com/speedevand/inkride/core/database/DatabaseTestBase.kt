package com.speedevand.inkride.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/**
 * An in-memory [AppDatabase] per test. In-memory rather than on-disk so tests
 * cannot leak state into each other through a file left on the device, and so
 * nothing has to be deleted between runs.
 *
 * Room turns foreign-key enforcement on for databases it opens, which is what
 * makes the cascade-delete assertions in the lap and track-point tests
 * meaningful.
 */
abstract class DatabaseTestBase {
    protected lateinit var db: AppDatabase
        private set

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
