package com.example.smartdispatch.engine

import android.util.Log
import com.example.smartdispatch.model.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFColor
import java.io.InputStream
import java.io.OutputStream

/**
 * 智能排工引擎
 */
class DispatchEngine {
    
    private var allPeople: List<String> = emptyList()
    private var skillScores: Map<String, Map<String, Int>> = emptyMap()
    private var processPriority: Map<String, Int> = emptyMap()
    private var productInfo: Map<String, Product> = emptyMap()
    private var leaveList: List<String> = emptyList()
    private var fixedPositions: Map<Pair<Int, Int>, String> = emptyMap()
    private var assignedPeople: MutableSet<String> = mutableSetOf()
    private var productColumnMap: Map<String, Int> = emptyMap()
    
    /**
     * 加载Excel数据
     * @return Pair<是否成功, 错误信息>
     */
    fun loadFromExcel(inputStream: InputStream): Pair<Boolean, String?> {
        return try {
            val workbook = XSSFWorkbook(inputStream)
            
            // 1. 读取工序评分表
            try {
                loadSkillScores(workbook)
            } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序评分表错误: ${e.message}")
            }
            
            // 2. 读取工序流程表
            try {
                loadProductInfo(workbook)
            } catch (e: Exception) {
                workbook.close()
                return Pair(false, "工序流程表错误: ${e.message}")
            }
            
            // 3. 读取智能排工主表
            try {
                loadMainSheet(workbook)
            } catch (e: Exception) {
                workbook.close()
                return Pair(false, "智能排工主表错误: ${e.message}")
            }
            
            workbook.close()
            Log.d("DispatchEngine", "数据加载成功: 人员${allPeople.size}人, 产品${productInfo.size}个")
            Pair(true, null)
        } catch (e: org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException) {
            Log.e("DispatchEngine", "不是有效的Office XML文件: ${e.message}")
            Pair(false, "不是有效的.xlsx文件，请确保文件格式正确")
        } catch (e: org.apache.poi.openxml4j.exceptions.InvalidFormatException) {
            Log.e("DispatchEngine", "文件格式无效: ${e.message}")
            Pair(false, "文件格式无效，请使用标准的.xlsx文件")
        } catch (e: Exception) {
            Log.e("DispatchEngine", "加载失败: ${e.message}")
            Pair(false, "加载失败: ${e.message}")
        }
    }
    
    /**
     * 执行排工
     */
    fun executeDispatch(): DispatchResult {
        assignedPeople.clear()
        
        // 处理固定岗位
        val availablePeople = allPeople.filter { it !in leaveList }
        
        // 构建全局工序队列
        val processQueue = buildProcessQueue()
        
        // 分配人员
        val assignments = mutableListOf<ProcessAssignment>()
        for (item in processQueue) {
            val (priority, productCol, inner) = item
            val (rowIndex, processName, productName) = inner
            val person = assignPerson(processName, availablePeople)
            if (person != null) {
                assignments.add(ProcessAssignment(
                    productName = productName,
                    processName = processName,
                    assignedPerson = person,
                    rowIndex = rowIndex,
                    columnIndex = productCol + 1
                ))
            }
        }
        
        // 计算统计
        val assignedCount = assignedPeople.size
        val remaining = availablePeople.size - assignedCount
        val unassigned = availablePeople.filter { it !in assignedPeople }
        
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
    
    /**
     * 导出结果到Excel
     */
    fun exportToExcel(inputStream: InputStream, outputStream: OutputStream, result: DispatchResult): Boolean {
        return try {
            val workbook = XSSFWorkbook(inputStream)
            val mainSheet = workbook.getSheet("智能排工")
            
            // 清除旧数据
            clearOldData(mainSheet)
            
            // 写入分配结果
            for (assignment in result.assignments) {
                val row = mainSheet.getRow(assignment.rowIndex - 1) ?: mainSheet.createRow(assignment.rowIndex - 1)
                val processCell = row.createCell(assignment.columnIndex - 2)
                processCell.setCellValue(assignment.processName)
                val personCell = row.createCell(assignment.columnIndex - 1)
                personCell.setCellValue(assignment.assignedPerson)
            }
            
            // 写入状态列
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
        
        val headerRow = sheet.getRow(0)
        if (headerRow == null) throw Exception("工序评分表表头为空")
        
        // 实际结构: A=技能评分/姓名, B=工号, C起=工序名(班长/组长/调机手...)
        // 跳过A列("技能评分")和B列("工号")，从C列开始才是工序名
        val allHeaders = (0 until headerRow.lastCellNum).map { 
            headerRow.getCell(it)?.stringCellValue ?: "" 
        }
        Log.d("DispatchEngine", "工序评分表头: $allHeaders")
        
        // 找到工序列的起始位置（跳过"技能评分"和"工号"）
        val processStartCol = allHeaders.indexOfFirst { it == "工号" }.let { 
            if (it >= 0) it + 1 else 2  // 工号列的下一列开始
        }
        
        val processNames = (processStartCol until headerRow.lastCellNum).map { 
            headerRow.getCell(it)?.stringCellValue ?: "" 
        }.filter { it.isNotEmpty() }
        
        Log.d("DispatchEngine", "工序名列表: $processNames, 起始列: $processStartCol")
        
        processPriority = processNames.withIndex().associate { it.value to it.index }
        
        val people = mutableListOf<String>()
        val scores = mutableMapOf<String, MutableMap<String, Int>>()
        
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            // A列是姓名
            val name = cleanName(row.getCell(0)?.stringCellValue)
            if (name != null) {
                people.add(name)
                scores[name] = mutableMapOf()
                for ((i, processName) in processNames.withIndex()) {
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
        
        val headerRow = sheet.getRow(0)
        if (headerRow == null) throw Exception("工序流程表表头为空")
        val headers = (0 until headerRow.lastCellNum).map { 
            headerRow.getCell(it)?.stringCellValue ?: "" 
        }
        
        val capacityCol = headers.indexOf("产能").takeIf { it >= 0 } ?: 1
        val peopleCol = headers.indexOf("人数").takeIf { it >= 0 } ?: 2
        val processCols = headers.mapIndexed { index, s -> if (s.startsWith("工序")) index else -1 }.filter { it >= 0 }
        
        Log.d("DispatchEngine", "工序流程表头: $headers")
        Log.d("DispatchEngine", "产能列: $capacityCol, 人数列: $peopleCol, 工序列: $processCols")
        
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
            
            Log.d("DispatchEngine", "产品: $productName, 产能: $capacity, 人数: $requiredPeople, 工序: $processes")
            products[productName] = Product(productName, capacity, requiredPeople, processes)
        }
        
        productInfo = products
    }
    
    private fun getCellIntValue(row: Row, colIndex: Int): Int {
        val cell = row.getCell(colIndex) ?: return 0
        return try {
            cell.numericCellValue.toInt()
        } catch (e: Exception) {
            try {
                cell.stringCellValue?.trim()?.toIntOrNull() ?: 0
            } catch (e2: Exception) {
                0
            }
        }
    }
    
    private fun loadMainSheet(workbook: Workbook) {
        val sheet = workbook.getSheet("智能排工") ?: throw Exception("缺失智能排工主表")
        
        // 读取请假人员
        val leaves = mutableListOf<String>()
        for (rowIndex in 1..DispatchConfig.MAX_PEOPLE) {
            val row = sheet.getRow(rowIndex) ?: continue
            val name = cleanName(row.getCell(0)?.stringCellValue)
            if (name != null) {
                leaves.add(name)
            }
        }
        leaveList = leaves
        
        // 读取产品列映射和固定岗位
        val colMap = mutableMapOf<String, Int>()
        val fixed = mutableMapOf<Pair<Int, Int>, String>()
        
        val headerRow = sheet.getRow(0)
        for (col in 1 until headerRow.lastCellNum step 2) {
            val productName = headerRow.getCell(col)?.stringCellValue ?: continue
            colMap[productName] = col
            
            // 检查是否为固定列（黄色背景）
            val cell = headerRow.getCell(col)
            val isFixed = isFixedColumn(cell)
            
            if (isFixed) {
                // 读取固定岗位人员
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
    
    private fun buildProcessQueue(): List<Triple<Int, Int, Triple<Int, String, String>>> {
        val queue = mutableListOf<Triple<Int, Int, Triple<Int, String, String>>>()
        
        for ((productName, product) in productInfo) {
            val productCol = productColumnMap[productName] ?: continue
            for ((offset, processName) in product.processes.withIndex()) {
                val priority = processPriority[processName] ?: Int.MAX_VALUE
                val rowIndex = 3 + offset // 从第3行开始
                queue.add(Triple(priority, productCol, Triple(rowIndex, processName, productName)))
            }
        }
        
        return queue.sortedBy { it.first }
    }
    
    private fun assignPerson(processName: String, availablePeople: List<String>): String? {
        // 检查固定岗位
        // ... (简化逻辑)
        
        // 动态分配
        val candidates = availablePeople
            .filter { it !in assignedPeople }
            .mapNotNull { person ->
                val score = skillScores[person]?.get(processName) ?: 0
                if (score > 0) person to score else null
            }
            .sortedByDescending { it.second }
        
        val bestPerson = candidates.firstOrNull()?.first
        if (bestPerson != null) {
            assignedPeople.add(bestPerson)
        }
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
        // 找到状态列
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
