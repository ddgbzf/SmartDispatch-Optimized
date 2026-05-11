package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.Assignment
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments ORDER BY productId, sortOrder")
    fun getAll(): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments WHERE productId = :productId ORDER BY sortOrder")
    fun getByProduct(productId: Int): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments WHERE isFixed = 1")
    fun getFixed(): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments WHERE isFixed = 1")
    suspend fun getFixedOnce(): List<Assignment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(assignment: Assignment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assignments: List<Assignment>)

    @Update
    suspend fun update(assignment: Assignment)

    @Delete
    suspend fun delete(assignment: Assignment)

    @Query("DELETE FROM assignments")
    suspend fun deleteAll()

    @Query("DELETE FROM assignments WHERE isFixed = 0")
    suspend fun deleteDynamic()
}
