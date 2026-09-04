package com.calltracker.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromDirection(d: CallDirection) = d.name
    @TypeConverter fun toDirection(s: String) = CallDirection.valueOf(s)
    @TypeConverter fun fromStatus(s: SyncStatus) = s.name
    @TypeConverter fun toStatus(s: String) = SyncStatus.valueOf(s)
    @TypeConverter fun fromCallStatus(s: CallStatus) = s.name
    @TypeConverter fun toCallStatus(s: String) = CallStatus.valueOf(s)
    @TypeConverter fun fromOutcome(o: ProcessingOutcome) = o.name
    @TypeConverter fun toOutcome(s: String) = ProcessingOutcome.valueOf(s)
}

@Database(
    entities = [
        CallEntity::class,
        ProcessedCallEntity::class,
        ExcludedNumberEntity::class,
        DiagnosticEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    abstract fun excludedNumberDao(): ExcludedNumberDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_tracker.db"
                )
                    // v1 -> v2 added status/endedAt/SIM/sync-attempt columns and the
                    // processed-marker + diagnostic tables. Nothing has ever shipped,
                    // and POC capture data is disposable, so a destructive upgrade is
                    // the honest choice here rather than a migration nobody will test.
                    // Write a real Migration before any pilot install.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
