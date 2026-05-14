package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.FixedCell
import kotlinx.coroutines.flow.Flow

@Dao
interface FixedCellDao {
    @Query("SELECT * FROM fixed_cells")
    fun getAll(): Flow<List<FixedCell>>
    
    @Query("SELECT * FROM fixed_cells WHERE colIndex = :colIndex")
    fun getByColumn(colIndex: Int): Flow<List<FixedCell>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cells: List<FixedCell>)
    
    @Query("DELETE FROM fixed_cells WHERE colIndex = :colIndex")
    suspend fun deleteByColumn(colIndex: Int)
    
    @Query("DELETE FROM fixed_cells")
    suspend fun deleteAll()
}
