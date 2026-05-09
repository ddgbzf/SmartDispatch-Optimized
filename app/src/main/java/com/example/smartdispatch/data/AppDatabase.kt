package com.example.smartdispatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*

@Database(
    entities = [Person::class, SkillScore::class, Product::class, ProductProcess::class, Assignment::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun skillScoreDao(): SkillScoreDao
    abstract fun productDao(): ProductDao
    abstract fun productProcessDao(): ProductProcessDao
    abstract fun assignmentDao(): AssignmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_dispatch.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
