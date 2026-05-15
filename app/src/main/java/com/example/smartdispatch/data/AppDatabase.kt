package com.example.smartdispatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*

@Database(
    entities = [Person::class, SkillScore::class, Product::class, ProductProcess::class, Assignment::class, FixedCell::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun skillScoreDao(): SkillScoreDao
    abstract fun productDao(): ProductDao
    abstract fun productProcessDao(): ProductProcessDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun fixedCellDao(): FixedCellDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // version 10 → 11: skill_scores 添加 sortOrder 列
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skill_scores ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // version 11 → 12: persons 添加 jobType 列
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE persons ADD COLUMN jobType TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_dispatch.db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
