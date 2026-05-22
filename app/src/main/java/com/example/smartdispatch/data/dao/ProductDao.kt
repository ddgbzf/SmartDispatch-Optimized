package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.Product
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id")
    fun getAll(): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: Product): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Update
    suspend fun update(product: Product)

    @Delete
    suspend fun delete(product: Product)

    @Query("DELETE FROM products")
    suspend fun deleteAll()

    @Query("SELECT * FROM products WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Product?

    @Query("SELECT * FROM products ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getPaged(offset: Int, limit: Int): List<Product>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getCount(): Int
}
