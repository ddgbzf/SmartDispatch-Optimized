package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fixed_cells",
    foreignKeys = [
        ForeignKey(entity = Person::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("personId"), Index(value = ["rowIndex", "colIndex"], unique = true)]
)
data class FixedCell(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rowIndex: Int,      // 行号（从0开始）
    val colIndex: Int,      // 列号（从0开始）
    val personId: Int       // 固定的人员ID
)
