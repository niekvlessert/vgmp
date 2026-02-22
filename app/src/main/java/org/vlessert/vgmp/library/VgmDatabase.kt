package org.vlessert.vgmp.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntity::class, TrackEntity::class],
    version = 4,
    exportSchema = false
)
abstract class VgmDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile private var INSTANCE: VgmDatabase? = null

        fun getInstance(context: Context): VgmDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VgmDatabase::class.java,
                    "vgmp.db"
                ).fallbackToDestructiveMigration()
                 .build()
                 .also { INSTANCE = it }
            }
        }
    }
}
