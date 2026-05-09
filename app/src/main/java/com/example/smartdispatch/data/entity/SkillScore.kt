package com.example.smartdispatch.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skill_scores",
    foreignKeys = [
        ForeignKey(entity = Person::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("personId")]
)
data class SkillScore(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val personId: Int,
    val processName: String,
    val score: Int = 0
)
