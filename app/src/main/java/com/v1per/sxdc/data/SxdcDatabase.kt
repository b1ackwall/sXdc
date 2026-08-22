package com.v1per.sxdc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The one database. Single table today; the version stays at 1 until something ships against it.
 *
 * `exportSchema = false` because nothing consumes a schema JSON yet -- there are no migration
 * tests and no shipped version to migrate from. Flip it on (and add the Room Gradle plugin) at
 * the same time as the first real migration, not before.
 *
 * No `setDriver`: on Android, Room still defaults to the framework SQLite driver, and the service
 * and the UI live in one process, so the default in-process InvalidationTracker is the right one.
 * Adding `android:process` to anything in the manifest would silently break Flow invalidation
 * across that boundary and would need `enableMultiInstanceInvalidation()`.
 */
@Database(entities = [DownloadJob::class], version = 1, exportSchema = false)
@TypeConverters(DownloadStatusConverter::class)
abstract class SxdcDatabase : RoomDatabase() {

    abstract fun downloadJobs(): DownloadJobDao

    companion object {
        private const val NAME = "sxdc.db"

        @Volatile
        private var instance: SxdcDatabase? = null

        /**
         * Reached from the Application, an Activity and the service, any of which can be the first
         * thing to run, so the double-checked lock is doing real work rather than ceremony.
         */
        fun get(context: Context): SxdcDatabase = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, SxdcDatabase::class.java, NAME)
                .build()
                .also { instance = it }
        }
    }
}
