package com.localai.toolkit.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's only database. Plain local storage - no sync, no export, no remote mirror.
 *
 * Schemas are exported to app/schemas so future migrations can be written against a
 * reviewed baseline rather than a guess.
 */
@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LocalAiDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "local_ai.db"
    }
}
