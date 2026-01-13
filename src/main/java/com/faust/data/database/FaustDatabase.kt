package com.faust.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.faust.models.BlockedApp
import com.faust.models.PointTransaction

@Database(
    entities = [BlockedApp::class, PointTransaction::class],
    version = 1,
    exportSchema = false
)
abstract class FaustDatabase : RoomDatabase() {
    abstract fun appBlockDao(): AppBlockDao
    abstract fun pointTransactionDao(): PointTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: FaustDatabase? = null

        fun getDatabase(context: Context): FaustDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FaustDatabase::class.java,
                    "faust_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
