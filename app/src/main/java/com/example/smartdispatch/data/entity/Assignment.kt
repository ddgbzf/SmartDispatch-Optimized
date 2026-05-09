package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "assignments",
    foreignKeys = [
        ForeignKey(entity = Product::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Person::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("productId"), Index("personId")]
)
data class Assignment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productId: Int,
    val processName: String,
    val personId: Int? = null,
    val isFixed: Boolean = false,
    val sortOrder: Int = 0
)
