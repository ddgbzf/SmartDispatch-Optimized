package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.SkillScore
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillScoreDao {
    @Query("SELECT * FROM skill_scores ORDER BY personId, sortOrder, processName")
    fun getAll(): Flow<List<SkillScore>>

    @Query("SELECT * FROM skill_scores WHERE personId = :personId")
    fun getByPerson(personId: Int): Flow<List<SkillScore>>

    @Query("SELECT processName FROM skill_scores GROUP BY processName ORDER BY MIN(sortOrder), MIN(id)")
    fun getAllProcessNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(score: SkillScore)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scores: List<SkillScore>)

    @Update
    suspend fun update(score: SkillScore)

    @Delete
    suspend fun delete(score: SkillScore)

    @Query("DELETE FROM skill_scores")
    suspend fun deleteAll()

    @Query("SELECT * FROM skill_scores WHERE personId = :personId AND processName = :processName LIMIT 1")
    suspend fun find(personId: Int, processName: String): SkillScore?

    @Query("SELECT * FROM skill_scores WHERE personId = :personId ORDER BY sortOrder, id")
    suspend fun getByPersonOnce(personId: Int): List<SkillScore>

    // 工序管理：删除某工序的所有评分
    @Query("DELETE FROM skill_scores WHERE processName = :processName")
    suspend fun deleteByProcessName(processName: String)

    // 工序管理：重命名工序
    @Query("UPDATE skill_scores SET processName = :newName WHERE processName = :oldName")
    suspend fun renameProcess(oldName: String, newName: String)

    // 工序管理：检查工序名是否存在
    @Query("SELECT COUNT(*) FROM skill_scores WHERE processName = :processName LIMIT 1")
    suspend fun processNameExists(processName: String): Int

    // 工序管理：获取所有工序名（非Flow版本）
    @Query("SELECT processName FROM skill_scores GROUP BY processName ORDER BY MIN(sortOrder), MIN(id)")
    suspend fun getAllProcessNamesOnce(): List<String>

    // 工序管理：获取某工序的最小sortOrder
    @Query("SELECT MIN(sortOrder) FROM skill_scores WHERE processName = :processName")
    suspend fun getMinSortOrder(processName: String): Int?

    // 工序管理：更新某工序的sortOrder
    @Query("UPDATE skill_scores SET sortOrder = :newOrder WHERE processName = :processName")
    suspend fun updateProcessSortOrder(processName: String, newOrder: Int)

    // 工序管理：获取所有工序及其sortOrder
    @Query("SELECT DISTINCT processName, MIN(sortOrder) as sortOrder, MIN(id) as minId FROM skill_scores GROUP BY processName ORDER BY MIN(sortOrder), MIN(id)")
    suspend fun getProcessOrders(): List<ProcessOrder>

    // 工序管理：将sortOrder >= targetOrder 的所有记录+1
    @Query("UPDATE skill_scores SET sortOrder = sortOrder + 1 WHERE sortOrder >= :targetOrder")
    suspend fun shiftSortOrder(targetOrder: Int)
}

// 用于查询工序排序
data class ProcessOrder(
    val processName: String,
    val sortOrder: Int,
    val minId: Int = 0
)
