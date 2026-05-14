package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.FixedCell
import kotlinx.coroutines.flow.Flow

@Dao
interface FixedCellDao {
    @Query("SELECT * FROM fixed_cells")
    fun getAll(): Flow<List<FixedCell>>
    
    @Query("SELECT * FROM fixed_cells WHERE rowIndex = :rowIndex AND colIndex = :colIndex")
    suspend fun getByPosition(rowIndex: Int, colIndex: Int): FixedCell?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fixed: FixedCell)
    
    @Delete
    suspend fun delete(fixed: FixedCell)
    
    @Query("DELETE FROM fixed_cells WHERE rowIndex = :rowIndex AND colIndex = :colIndex")
    suspend fun deleteByPosition(rowIndex: Int, colIndex: Int)
    
    @Query("DELETE FROM fixed_cells WHERE personId = :personId")
    suspend fun deleteByPerson(personId: Int)
    
    @Query("DELETE FROM fixed_cells")
    suspend fun deleteAll()
}
