package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_processes",
    foreignKeys = [
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("productId")]
)
data class ProductProcess(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productId: Int,
    val processName: String,
    val sortOrder: Int = 0
)
