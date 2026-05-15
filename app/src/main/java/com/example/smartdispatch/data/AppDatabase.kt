package com.example.smartdispatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // 预置示例数据的 Callback
        private val prepopulateCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // 数据库首次创建后，在协程中插入示例数据
                CoroutineScope(Dispatchers.IO).launch {
                    val database = INSTANCE ?: return@launch
                    val personDao = database.personDao()
                    val skillScoreDao = database.skillScoreDao()
                    val productDao = database.productDao()
                    val productProcessDao = database.productProcessDao()

                    // 检查是否已有数据，避免重复插入
                    val personCount = getCount(personDao)
                    if (personCount > 0) return@launch

                    // 插入6个人员
                    val persons = listOf(
                        Person(name = "张三", employeeId = "001", jobType = "焊工", insertOrder = 1),
                        Person(name = "李四", employeeId = "002", jobType = "折边工", insertOrder = 2),
                        Person(name = "王五", employeeId = "003", jobType = "打磨工", insertOrder = 3),
                        Person(name = "赵六", employeeId = "004", jobType = "组装工", insertOrder = 4),
                        Person(name = "陈七", employeeId = "005", jobType = "检验员", insertOrder = 5),
                        Person(name = "刘八", employeeId = "006", jobType = "搬运工", insertOrder = 6)
                    )
                    personDao.insertAll(persons)

                    // 获取插入后的人员（带id）
                    val allPersons = personDao.getAllOnce()
                    if (allPersons.isEmpty()) return@launch

                    // 插入工序评分（4个工序：折边、焊接、打磨、组装）
                    val scoreData = mapOf(
                        "张三" to mapOf("折边" to 5, "焊接" to 9, "打磨" to 3, "组装" to 4),
                        "李四" to mapOf("折边" to 9, "焊接" to 4, "打磨" to 5, "组装" to 3),
                        "王五" to mapOf("折边" to 3, "焊接" to 5, "打磨" to 9, "组装" to 4),
                        "赵六" to mapOf("折边" to 4, "焊接" to 3, "打磨" to 5, "组装" to 9),
                        "陈七" to mapOf("折边" to 7, "焊接" to 6, "打磨" to 7, "组装" to 8),
                        "刘八" to mapOf("折边" to 2, "焊接" to 3, "打磨" to 4, "组装" to 5)
                    )
                    val processOrder = mapOf("折边" to 0, "焊接" to 1, "打磨" to 2, "组装" to 3)
                    val scores = mutableListOf<SkillScore>()
                    for (person in allPersons) {
                        val personScores = scoreData[person.name] ?: continue
                        for ((processName, score) in personScores) {
                            scores.add(SkillScore(
                                personId = person.id,
                                processName = processName,
                                score = score,
                                sortOrder = processOrder[processName] ?: 0
                            ))
                        }
                    }
                    skillScoreDao.insertAll(scores)

                    // 插入2个产品
                    val productA = Product(name = "产品A", capacity = 3000, requiredPeople = 8)
                    val productB = Product(name = "产品B", capacity = 1500, requiredPeople = 5)
                    val idA = productDao.insert(productA)
                    val idB = productDao.insert(productB)

                    // 插入产品工序
                    // 产品A: 折边→焊接→打磨→组装
                    productProcessDao.insertAll(listOf(
                        ProductProcess(productId = idA.toInt(), processName = "折边", sortOrder = 0),
                        ProductProcess(productId = idA.toInt(), processName = "焊接", sortOrder = 1),
                        ProductProcess(productId = idA.toInt(), processName = "打磨", sortOrder = 2),
                        ProductProcess(productId = idA.toInt(), processName = "组装", sortOrder = 3)
                    ))
                    // 产品B: 折边→焊接→组装
                    productProcessDao.insertAll(listOf(
                        ProductProcess(productId = idB.toInt(), processName = "折边", sortOrder = 0),
                        ProductProcess(productId = idB.toInt(), processName = "焊接", sortOrder = 1),
                        ProductProcess(productId = idB.toInt(), processName = "组装", sortOrder = 2)
                    ))
                }
            }
        }

        // 辅助方法：获取人员数量（通过原始SQL查询）
        private suspend fun getCount(personDao: PersonDao): Int {
            // 使用 findByName 无法判断数量，直接用 DAO 的方式
            // 由于 PersonDao 没有 count 方法，通过 getAllOnce 判断
            return try {
                val db = INSTANCE ?: return 0
                val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM persons")
                cursor.moveToFirst()
                val count = cursor.getInt(0)
                cursor.close()
                count
            } catch (_: Exception) {
                0
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
                    .addCallback(prepopulateCallback)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
