package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fixed_cells")
data class FixedCell(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val colIndex: Int,      // 列索引
    val rowIndex: Int,      // 行索引
    val personName: String  // 人员姓名
)
