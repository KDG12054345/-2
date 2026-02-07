package com.faust.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.faust.models.AppGroup
import com.faust.models.BlockedApp
import com.faust.models.BlockedDomain
import com.faust.models.PointTransaction

@Database(
    entities = [
        BlockedApp::class,
        PointTransaction::class,
        AppGroup::class,
        BlockedDomain::class
    ],
    version = 4,
    exportSchema = false
)
abstract class FaustDatabase : RoomDatabase() {
    abstract fun appBlockDao(): AppBlockDao
    abstract fun pointTransactionDao(): PointTransactionDao
    abstract fun appGroupDao(): AppGroupDao
    abstract fun blockedDomainDao(): BlockedDomainDao

    companion object {
        @Volatile
        private var INSTANCE: FaustDatabase? = null

        /**
         * 버전 1에서 2로의 Migration
         * 새 테이블 추가: free_pass_items, daily_usage_records, app_groups
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // free_pass_items 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS free_pass_items (
                        itemType TEXT NOT NULL PRIMARY KEY,
                        quantity INTEGER NOT NULL DEFAULT 0,
                        lastPurchaseTime INTEGER NOT NULL DEFAULT 0,
                        lastUseTime INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // daily_usage_records 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_usage_records (
                        date TEXT NOT NULL PRIMARY KEY,
                        standardTicketUsedCount INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // app_groups 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_groups (
                        packageName TEXT NOT NULL,
                        groupType TEXT NOT NULL,
                        isIncluded INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (packageName, groupType)
                    )
                """.trimIndent())
            }
        }

        /**
         * 버전 2에서 3으로의 Migration
         * 새 테이블 추가: blocked_domains (URL 차단용)
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // blocked_domains 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS blocked_domains (
                        domain TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT,
                        blockedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * 버전 3에서 4로의 Migration
         * FreePass 관련 테이블 제거: free_pass_items, daily_usage_records
         * TimeCreditSystem으로 전환 (데이터는 SharedPreferences에 저장)
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // FreePass 관련 테이블 삭제
                database.execSQL("DROP TABLE IF EXISTS free_pass_items")
                database.execSQL("DROP TABLE IF EXISTS daily_usage_records")
            }
        }

        fun getDatabase(context: Context): FaustDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FaustDatabase::class.java,
                    "faust_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration() // Migration 실패 시 폴백
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
