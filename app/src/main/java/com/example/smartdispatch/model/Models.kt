package com.example.smartdispatch.model

import kotlinx.serialization.Serializable

/**
 * 产品信息
 */
data class Product(
    val name: String,
    val capacity: Int = 0,      // 产能
    val requiredPeople: Int = 0, // 需求人数
    val processes: List<String> = emptyList(), // 工序列表
    val isFixed: Boolean = false  // 固定状态标记
)

/**
 * 工序分配结果
 */
@Serializable
data class ProcessAssignment(
    val productName: String,
    val processName: String,
    val assignedPerson: String?,
    val rowIndex: Int,
    val columnIndex: Int,
    val inputIndex: Int = -1
)

/**
 * 排工结果
 */
@Serializable
data class DispatchResult(
    val assignments: List<ProcessAssignment>,
    val totalPeople: Int,
    val leaveCount: Int,
    val assignedCount: Int,
    val remainingCount: Int,
    val unassignedPeople: List<String>,
    val statusMessage: String,
    val debugLogs: List<String> = emptyList()  // 调试日志
)

/**
 * 技能评分记录
 */
data class SkillScore(
    val personName: String,
    val processName: String,
    val score: Int
)

/**
 * 固定岗位
 */
data class FixedPosition(
    val productName: String,
    val processName: String,
    val personName: String,
    val column: Int,
    val row: Int
)

/**
 * 排工配置
 */
object DispatchConfig {
    const val MAX_PEOPLE = 100
    const val FIXED_COLUMN_COLOR = "FFFFFF00" // 黄色
}
