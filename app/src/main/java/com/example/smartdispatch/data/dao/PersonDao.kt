package com.example.smartdispatch.data.dao

import androidx.room.*
import com.example.smartdispatch.data.entity.Person
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons ORDER BY name")
    fun getAll(): Flow<List<Person>>

    @Query("SELECT * FROM persons WHERE onLeave = 1 ORDER BY name")
    fun getOnLeave(): Flow<List<Person>>

    @Query("SELECT * FROM persons WHERE onLeave = 0 ORDER BY name")
    fun getAvailable(): Flow<List<Person>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: Person): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(persons: List<Person>)

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Person?
}
