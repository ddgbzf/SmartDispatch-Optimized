package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.SkillScore
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillScoreDao {
    @Query("SELECT * FROM skill_scores ORDER BY personId, processName")
    fun getAll(): Flow<List<SkillScore>>

    @Query("SELECT * FROM skill_scores WHERE personId = :personId")
    fun getByPerson(personId: Int): Flow<List<SkillScore>>

    @Query("SELECT DISTINCT processName FROM skill_scores ORDER BY id")
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

    @Query("SELECT * FROM skill_scores WHERE personId = :personId ORDER BY id")
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
    @Query("SELECT DISTINCT processName FROM skill_scores ORDER BY id")
    suspend fun getAllProcessNamesOnce(): List<String>
}
