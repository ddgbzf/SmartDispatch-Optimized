package com.example.smartdispatch.engine

import android.util.Log
import com.example.smartdispatch.model.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFColor
import java.io.InputStream
import java.io.OutputStream

data class ParsedData(
    val people: List<String>,
    val leaveList: List<String>,
    val skillScores: Map<String, Map<String, Int>>,
    val processNames: List<String>,
    val products: Map<String, Product>
)

// 用于工序队列的数据类，避免复杂的泛型嵌套
private data class ProcessQueueItem(
    val priority: Int,
    val productCol: Int,
    val rowIndex: Int,
    val processName: String,
    val productName: String
)

class DispatchEngine {

    private var allPeople: List<String> = emptyList()
    private var skillScores: Map<String, Map<String, Int>> = emptyMap()
    private var processPriority: Map<String, Int> = emptyMap()
    private var productInfo: Map<String, Product> = emptyMap()
    private var leaveList: List<String> = emptyList()
    private var fixedPositions: Map<Pair<Int, Int>, String> = emptyMap()
    private var assignedPeople: MutableSet<String> = mutableSetOf()
    private var productColumnMap: Map<String, Int> = emptyMap()
    private var parsedProcessNames: List<String> = emptyList()

    fun loadFromExcel(inputStream: InputStream): Pair<Boolean, String?> {
        return try {
            val workbook = XSSFWorkbook(inputStream)

            try { loadSkillScores(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序评分表错误: ${e.message}")
            }

            try { loadProductInfo(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序流程表错误: ${e.message}")
            }

            try { loadMainSheet(workbook) } catch (e: Exception) {
                workbook.close()
                return Pair(false, "智能排工主表错误: ${e.message}")
            }

            workbook.close()
            Log.d("DispatchEngine", "数据加载成功: 人员${allPeople.size}人, 产品${productInfo.size}个")
            Pair(true, null)
        } catch (e: org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException) {
            Pair(false, "不是有效的.xlsx文件，请确保文件格式正确")
        } catch (e: org.apache.poi.openxml4j.exceptions.InvalidFormatException) {
            Pair(false, "文件格式无效，请使用标准的.xlsx文件")
        } catch (e: Exception) {
            Pair(false, "加载失败: ${e.message}")
        }
    }

    fun getParsedData(): ParsedData {
        return ParsedData(
            people = allPeople,
            leaveList = leaveList,
            skillScores = skillScores,
            processNames = parsedProcessNames,
            products = productInfo
        )
    }

    /**
     * 使用数据库传入的数据执行排工
     */
    fun runWithData(
        people: List<String>,
        leaveNames: List<String>,
        products: Map<String, Product>,
        processNames: List<String>
    ): DispatchResult {
        allPeople = people
        leaveList = leaveNames
        productInfo = products
        parsedProcessNames = processNames
        processPriority = processNames.withIndex().associate { it.value to it.index }
        // 为数据库来源的数据自动构建 productColumnMap
        productColumnMap = products.keys.withIndex().associate { (index, name) -> name to (index * 2 + 1) }
        // skillScores 从数据库加载时需要通过 setSkillScores 设置
        return executeDispatch()
    }

    fun setSkillScoresData(scores: Map<String, Map<String, Int>>) {
        skillScores = scores
    }

    fun executeDispatch(): DispatchResult {
        assignedPeople.clear()
        val processQueue = buildProcessQueue()
        Log.d("DispatchEngine", "工序队列大小: ${processQueue.size}, 产品数: ${productInfo.size}, 评分人数: ${skillScores.size}")

        val assignments = mutableListOf<ProcessAssignment>()
        for (item in processQueue) {
            val currentAvailable = allPeople.filter { it !in leaveList && it !in assignedPeople }
            val person = assignPerson(item.processName, currentAvailable)
            if (person != null) {
                assignments.add(ProcessAssignment(
                    productName = item.productName,
                    processName = item.processName,
                    assignedPerson = person,
                    rowIndex = item.rowIndex,
                    columnIndex = item.productCol + 1
                ))
            }
        }

        val assignedCount = assignedPeople.size
        val finalAvailable = allPeople.filter { it !in leaveList }
        // 总需求人数 = 各产品的需求人数之和（和py脚本一致）
        val totalDemand = productInfo.values.sumOf { it.requiredPeople }
        val remaining = finalAvailable.size - totalDemand
        val unassigned = finalAvailable.filter { it !in assignedPeople }

        Log.d("DispatchEngine", "分配结果: 总${allPeople.size}, 请假${leaveList.size}, 可用${finalAvailable.size}, 已分${assignedCount}, 总需求${totalDemand}, 差值${remaining}")

        return DispatchResult(
            assignments = assignments,
            totalPeople = allPeople.size,
            leaveCount = leaveList.size,
            assignedCount = assignedCount,
            remainingCount = remaining,
            unassignedPeople = unassigned,
            statusMessage = if (remaining >= 0) "剩余${remaining}人" else "欠缺${-remaining}人"
        )
    }

    fun exportToExcel(inputStream: InputStream, outputStream: OutputStream, result: DispatchResult): Boolean {
        return try {
            val workbook = XSSFWorkbook(inputStream)
            val mainSheet = workbook.getSheet("智能排工")
            clearOldData(mainSheet)
            for (assignment in result.assignments) {
                val row = mainSheet.getRow(assignment.rowIndex - 1) ?: mainSheet.createRow(assignment.rowIndex - 1)
                row.createCell(assignment.columnIndex - 2).setCellValue(assignment.processName)
                row.createCell(assignment.columnIndex - 1).setCellValue(assignment.assignedPerson)
            }
            writeStatusColumn(mainSheet, result)
            workbook.write(outputStream)
            workbook.close()
            true
        } catch (e: Exception) {
            Log.e("DispatchEngine", "导出失败: ${e.message}")
            false
        }
    }

    private fun loadSkillScores(workbook: Workbook) {
        val sheet = workbook.getSheet("工序评分") ?: throw Exception("缺失工序评分表")
        val headerRow = sheet.getRow(0) ?: throw Exception("工序评分表表头为空")

        val allHeaders = (0 until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }
        Log.d("DispatchEngine", "工序评分表头: $allHeaders")

        val processStartCol = allHeaders.indexOfFirst { it == "工号" }.let {
            if (it >= 0) it + 1 else 2
        }

        parsedProcessNames = (processStartCol until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }.filter { it.isNotEmpty() }

        Log.d("DispatchEngine", "工序名列表: $parsedProcessNames, 起始列: $processStartCol")

        processPriority = parsedProcessNames.withIndex().associate { it.value to it.index }

        val people = mutableListOf<String>()
        val scores = mutableMapOf<String, MutableMap<String, Int>>()

        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val name = cleanName(row.getCell(0)?.stringCellValue)
            if (name != null) {
                people.add(name)
                scores[name] = mutableMapOf()
                for ((i, processName) in parsedProcessNames.withIndex()) {
                    val col = processStartCol + i
                    val score = getCellIntValue(row, col)
                    scores[name]!![processName] = score
                }
            }
        }

        allPeople = people
        skillScores = scores
    }

    private fun loadProductInfo(workbook: Workbook) {
        val sheet = workbook.getSheet("工序流程") ?: throw Exception("缺失工序流程表")
        val headerRow = sheet.getRow(0) ?: throw Exception("工序流程表表头为空")
        val headers = (0 until headerRow.lastCellNum).map {
            headerRow.getCell(it)?.stringCellValue ?: ""
        }

        val capacityCol = headers.indexOf("产能").takeIf { it >= 0 } ?: 1
        val peopleCol = headers.indexOf("人数").takeIf { it >= 0 } ?: 2
        val processCols = headers.mapIndexed { index, s -> if (s.startsWith("工序")) index else -1 }.filter { it >= 0 }

        Log.d("DispatchEngine", "工序流程表头: $headers")

        val products = mutableMapOf<String, Product>()

        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val productName = row.getCell(0)?.stringCellValue?.trim() ?: continue
            if (productName.isEmpty()) continue

            val capacity = getCellIntValue(row, capacityCol)
            val requiredPeople = getCellIntValue(row, peopleCol)
            val processes = processCols.mapNotNull { col ->
                row.getCell(col)?.stringCellValue?.trim()?.takeIf { it.isNotEmpty() }
            }

            products[productName] = Product(productName, capacity, requiredPeople, processes)
        }

        productInfo = products
    }

    private fun getCellIntValue(row: Row, colIndex: Int): Int {
        val cell = row.getCell(colIndex) ?: return 0
        return try {
            cell.numericCellValue.toInt()
        } catch (e: Exception) {
            try { cell.stringCellValue?.trim()?.toIntOrNull() ?: 0 } catch (e2: Exception) { 0 }
        }
    }

    private fun loadMainSheet(workbook: Workbook) {
        val sheet = workbook.getSheet("智能排工") ?: throw Exception("缺失智能排工主表")

        val leaves = mutableListOf<String>()
        for (rowIndex in 1..DispatchConfig.MAX_PEOPLE) {
            val row = sheet.getRow(rowIndex) ?: continue
            val name = cleanName(row.getCell(0)?.stringCellValue)
            if (name != null) leaves.add(name)
        }
        leaveList = leaves

        val colMap = mutableMapOf<String, Int>()
        val fixed = mutableMapOf<Pair<Int, Int>, String>()

        val headerRow = sheet.getRow(0)
        for (col in 1 until headerRow.lastCellNum step 2) {
            val productName = headerRow.getCell(col)?.stringCellValue ?: continue
            colMap[productName] = col

            val cell = headerRow.getCell(col)
            if (isFixedColumn(cell)) {
                for (row in 2 until DispatchConfig.MAX_PEOPLE + 2) {
                    val dataRow = sheet.getRow(row) ?: continue
                    val personName = cleanName(dataRow.getCell(col + 1)?.stringCellValue)
                    if (personName != null && personName !in leaveList) {
                        fixed[Pair(col + 1, row + 1)] = personName
                        assignedPeople.add(personName)
                    }
                }
            }
        }

        productColumnMap = colMap
        fixedPositions = fixed
    }

    private fun buildProcessQueue(): List<ProcessQueueItem> {
        val queue = mutableListOf<ProcessQueueItem>()
        for ((productName, product) in productInfo) {
            val productCol = productColumnMap[productName] ?: continue
            // 固定产品优先级为负数，确保排在前面
            val fixedBonus = if (product.isFixed) -10000 else 0
            for ((offset, processName) in product.processes.withIndex()) {
                val priority = (processPriority[processName] ?: Int.MAX_VALUE) + fixedBonus
                val rowIndex = 3 + offset
                queue.add(ProcessQueueItem(priority, productCol, rowIndex, processName, productName))
            }
        }
        // 排序规则：优先级 → 产品列号 → 行偏移（与py脚本一致）
        return queue.sortedWith(compareBy({ it.priority }, { it.productCol }, { it.rowIndex }))
    }

    private fun assignPerson(processName: String, availablePeople: List<String>): String? {
        val candidates = availablePeople
            .filter { it !in assignedPeople }
            .mapNotNull { person ->
                val score = skillScores[person]?.get(processName) ?: 0
                // 调试日志：记录评分查询结果
                Log.d("DispatchEngine", "评分查询: $person / $processName = $score")
                if (score > 0) person to score else null
            }
            .sortedByDescending { it.second }

        val bestPerson = candidates.firstOrNull()?.first
        Log.d("DispatchEngine", "工序[$processName] 候选人: ${candidates.size}人, 选中: ${bestPerson ?: "无"}")
        if (bestPerson != null) assignedPeople.add(bestPerson)
        return bestPerson
    }

    private fun clearOldData(sheet: Sheet) {
        for (row in 1..23) {
            val r = sheet.getRow(row) ?: continue
            for (col in 1..19) {
                r.getCell(col)?.setCellValue(null as String?)
            }
        }
    }

    private fun writeStatusColumn(sheet: Sheet, result: DispatchResult) {
        val statusCol = productColumnMap.values.maxOrNull()?.plus(2) ?: 10
        val statusRow = sheet.getRow(1) ?: sheet.createRow(1)
        statusRow.createCell(statusCol).setCellValue(result.statusMessage)
        for ((index, person) in result.unassignedPeople.withIndex()) {
            val row = sheet.getRow(2 + index) ?: sheet.createRow(2 + index)
            row.createCell(statusCol).setCellValue(person)
        }
    }

    private fun isFixedColumn(cell: Cell?): Boolean {
        if (cell == null) return false
        val fill = cell.cellStyle.fillForegroundColorColor
        return fill is XSSFColor && fill.argbHex?.uppercase() == DispatchConfig.FIXED_COLUMN_COLOR
    }

    private fun cleanName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val cleaned = name.trim()
        return if (cleaned.length in 2..20) cleaned else null
    }
}
