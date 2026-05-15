package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skill_scores")
data class SkillScore(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val personId: Int,
    val processName: String,
    val score: Int,
    val sortOrder: Int = 0  // 工序排序，越小越靠前
)
