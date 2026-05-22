package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.ProductProcess
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductProcessDao {
    @Query("SELECT * FROM product_processes WHERE productId = :productId ORDER BY sortOrder")
    fun getByProduct(productId: Int): Flow<List<ProductProcess>>

    @Query("SELECT * FROM product_processes ORDER BY productId, sortOrder")
    fun getAll(): Flow<List<ProductProcess>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(process: ProductProcess)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(processes: List<ProductProcess>)

    @Update
    suspend fun update(process: ProductProcess)

    @Delete
    suspend fun delete(process: ProductProcess)

    @Query("DELETE FROM product_processes WHERE productId = :productId")
    suspend fun deleteByProduct(productId: Int)

    @Query("DELETE FROM product_processes")
    suspend fun deleteAll()

    @Query("SELECT * FROM product_processes WHERE productId = :productId ORDER BY sortOrder")
    suspend fun getByProductOnce(productId: Int): List<ProductProcess>

    @Query("SELECT * FROM product_processes WHERE productId IN (:productIds) ORDER BY productId, sortOrder")
    suspend fun getByProductIds(productIds: List<Int>): List<ProductProcess>
}
