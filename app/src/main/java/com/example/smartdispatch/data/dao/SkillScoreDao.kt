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

    @Query("SELECT DISTINCT processName FROM skill_scores ORDER BY processName")
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
}
