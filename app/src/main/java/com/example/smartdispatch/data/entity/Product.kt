package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val capacity: Int = 0,
    val requiredPeople: Int = 0,
    val isFixed: Boolean = false  // 固定状态标记
)
