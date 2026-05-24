package com.example.smartdispatch

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartdispatch.data.entity.*
import com.example.smartdispatch.engine.DispatchEngine
import com.example.smartdispatch.model.DispatchResult
import com.example.smartdispatch.model.ProcessAssignment
import com.example.smartdispatch.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as DispatchApplication).repository
    private val userPrefs = (application as DispatchApplication).userPrefs
    private val prefs = application.getSharedPreferences("dispatch_state", Context.MODE_PRIVATE)

    val allPersons = repo.allPersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val leavePersons = repo.leavePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val availablePersons = repo.availablePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProcessNames = repo.allProcessNames.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProducts = repo.allProducts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allAssignments = repo.allAssignments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentProducts = userPrefs.recentProducts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allFixedCells = repo.allFixedCells.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _logs = MutableStateFlow(listOf<String>())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _dispatchResult = MutableStateFlow<DispatchResult?>(null)
    val dispatchResult: StateFlow<DispatchResult?> = _dispatchResult.asStateFlow()

    // 标记是否已执行过启动排工
    var hasAutoDispatchedOnLaunch = false

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun loadDispatchResult(): DispatchResult? {
        val jsonStr = prefs.getString("last_dispatch_result", null) ?: return null
        return try {
            json.decodeFromString(DispatchResult.serializer(), jsonStr)
        } catch (e: Exception) { null }
    }

    private fun saveDispatchResult(result: DispatchResult?) {
        prefs.edit().apply {
            if (result != null) {
                putString("last_dispatch_result", json.encodeToString(DispatchResult.serializer(), result))
            } else {
                remove("last_dispatch_result")
            }
            apply()
        }
    }
    // 固定列人员缓存（上次排工中被分配到固定列产品的人员）
    private val _fixedPeople = MutableStateFlow<Set<String>>(emptySet())
    val fixedPeople: StateFlow<Set<String>> = _fixedPeople.asStateFlow()
    // 固定列输入槽位索引集合（哪些输入框被标记为固定列）
    private val _fixedInputSlots = MutableStateFlow(loadFixedInputSlots())
    val fixedInputSlots: StateFlow<Set<Int>> = _fixedInputSlots.asStateFlow()
    // 显示设置：字体大小、行高、列宽
    private val _fontSize = MutableStateFlow(prefs.getFloat("display_fontSize", 11f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    private val _rowHeight = MutableStateFlow(prefs.getFloat("display_rowHeight", 36f))
    val rowHeight: StateFlow<Float> = _rowHeight.asStateFlow()
    private val _colWidth = MutableStateFlow(prefs.getFloat("display_colWidth", 80f))
    val colWidth: StateFlow<Float> = _colWidth.asStateFlow()

    fun adjustFontSize(delta: Float) {
        val newVal = (_fontSize.value + delta).coerceIn(8f, 20f)
        _fontSize.value = newVal
        prefs.edit().putFloat("display_fontSize", newVal).apply()
    }
    fun adjustRowHeight(delta: Float) {
        val newVal = (_rowHeight.value + delta).coerceIn(20f, 80f)
        _rowHeight.value = newVal
        prefs.edit().putFloat("display_rowHeight", newVal).apply()
    }
    fun adjustColWidth(delta: Float) {
        val newVal = (_colWidth.value + delta).coerceIn(40f, 200f)
        _colWidth.value = newVal
        prefs.edit().putFloat("display_colWidth", newVal).apply()
    }

    // 固定单元格方法
    fun saveFixedCells(colIndex: Int, cells: List<FixedCell>) {
        viewModelScope.launch { repo.saveFixedCells(colIndex, cells) }
    }
    fun deleteFixedCellsByColumn(colIndex: Int) {
        viewModelScope.launch { repo.deleteFixedCellsByColumn(colIndex) }
    }

    // 工序评分管理
    fun deleteScoreProcess(processName: String) {
        viewModelScope.launch { repo.deleteProcess(processName) }
    }
    fun renameScoreProcess(oldName: String, newName: String) {
        viewModelScope.launch { repo.renameProcess(oldName, newName) }
    }
    fun addScoreProcess(processName: String, beforeProcess: String? = null) {
        viewModelScope.launch {
            val persons = repo.allPersons.first()
            repo.addProcessForAllPersons(processName, persons, beforeProcess)
        }
    }

    private fun loadFixedInputSlots(): Set<Int> {
        val str = prefs.getString("fixed_slots", "") ?: ""
        return if (str.isBlank()) emptySet() else str.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun saveFixedInputSlots(slots: Set<Int>) {
        prefs.edit().putString("fixed_slots", slots.joinToString(",")).apply()
    }

    fun toggleFixedSlot(index: Int) {
        val current = _fixedInputSlots.value.toMutableSet()
        if (index in current) {
            current.remove(index)
        } else {
            current.add(index)
        }
        _fixedInputSlots.value = current
        saveFixedInputSlots(current)
    }

    fun isInputSlotFixed(index: Int): Boolean = index in _fixedInputSlots.value

    // 输入框状态
    private val _inputNames = MutableStateFlow(loadInputNames())
    val inputNames: StateFlow<List<String>> = _inputNames.asStateFlow()

    private fun loadInputNames(): List<String> {
        return (1..7).map { i -> prefs.getString("input_$i", "") ?: "" }
    }

    private fun saveInputNames(names: List<String>) {
        prefs.edit().apply {
            names.forEachIndexed { i, v -> putString("input_${i+1}", v) }
            apply()
        }
    }
    private val _focusedInputIndex = MutableStateFlow(-1)
    val focusedInputIndex: StateFlow<Int> = _focusedInputIndex.asStateFlow()

    fun updateInputName(index: Int, name: String) {
        val list = _inputNames.value.toMutableList()
        list[index] = name
        _inputNames.value = list
    }

    fun setFocusedInputIndex(index: Int) {
        _focusedInputIndex.value = index
    }

    // 匹配的产品列表（按输入框）
    val matchedProducts: StateFlow<List<String>> = combine(_inputNames, _focusedInputIndex, allProducts, recentProducts) { names, focusIndex, products, recent ->
        if (focusIndex < 0 || focusIndex >= names.size) return@combine emptyList()
        val input = names[focusIndex].trim()
        if (input.isBlank()) return@combine emptyList()

        val productNames = products.map { it.name }
        val exactMatches = productNames.filter { it.equals(input, ignoreCase = true) }
        if (exactMatches.isNotEmpty()) return@combine exactMatches.take(5)

        val fuzzyMatches = productNames.filter { it.contains(input, ignoreCase = true) }
        if (fuzzyMatches.isNotEmpty()) return@combine fuzzyMatches.take(5)

        // 无匹配时显示最近使用产品
        recent.filter { rp -> productNames.any { it.equals(rp, ignoreCase = true) } }
            .take(5)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 排工状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 工序版本号（用于触发刷新）
    private val _processVersion = MutableStateFlow(0)
    val processVersion: StateFlow<Int> = _processVersion.asStateFlow()
    // 评分版本号
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()

    // 产品输入框数量
    private val _inputCount = MutableStateFlow(3)
    val inputCount: StateFlow<Int> = _inputCount.asStateFlow()

    fun setInputCount(count: Int) {
        _inputCount.value = count.coerceIn(1, 7)
    }

    // 产品输入框固定状态
    private val _fixedSlots = MutableStateFlow(loadFixedSlots())
    val fixedSlots: StateFlow<Set<Int>> = _fixedSlots.asStateFlow()

    private fun loadFixedSlots(): Set<Int> {
        val str = prefs.getString("fixed_slots", "") ?: ""
        return if (str.isBlank()) emptySet() else str.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun saveFixedSlots(slots: Set<Int>) {
        prefs.edit().putString("fixed_slots", slots.joinToString(",")).apply()
    }

    fun toggleSlotFixed(slot: Int) {
        val current = _fixedSlots.value.toMutableSet()
        if (slot in current) current.remove(slot) else current.add(slot)
        _fixedSlots.value = current
        saveFixedSlots(current)
    }

    fun isSlotFixed(slot: Int): Boolean = slot in _fixedSlots.value

    // 日志
    private fun addLog(msg: String) {
        _logs.value = _logs.value + msg
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // 排工核心逻辑
    private suspend fun executeDispatchInternal(selectedProductNames: List<String>) {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val engine = DispatchEngine()
            val persons = allPersons.first()
            val allProductsList = allProducts.first()
            val productByName = allProductsList.associateBy { it.name }
            val processNames = allProcessNames.first()
            val peopleNames = persons.map { it.name }
            val leaveNames = persons.filter { it.onLeave }.map { it.name }

            // 构建技能评分数据
            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            addLog("评分人数: ${scoreMap.size}, 工序优先级数: ${processNames.size}")

            engine.setSkillScoresData(scoreMap)

            // 按输入框槽位构建唯一产品 key，支持同一型号多列同时排工
            val productMap = mutableMapOf<String, Product>()
            selectedProductNames.forEachIndexed { slotIndex, name ->
                val product = productByName[name]
                if (product != null) {
                    val key = inputProductKey(slotIndex, name)
                    val processes = repo.getProcessesOnce(product.id)
                    productMap[key] = Product(
                        "$slotIndex:${name.trim()}", product.capacity, product.requiredPeople,
                        processes.map { it.processName }, product.isFixed
                    )
                }
            }
            addLog("排工产品数: ${productMap.size}")

            // ===== 第一步：检测固定列，从上次排工结果中读取人员 =====
            val fixedSlotSet = _fixedInputSlots.value.toMutableSet()
            val lastResult = _dispatchResult.value
            // 按输入框槽位读取上次产品名（新结果用 inputIndex，旧结果回退到列号）
            fun displayProductName(rawName: String): String = rawName.substringAfter(":")
            val lastProductKeys = lastResult?.assignments
                ?.groupBy { assignment -> if (assignment.inputIndex >= 0) assignment.inputIndex else (assignment.columnIndex - 1) / 2 }
                ?.toSortedMap()
                ?.map { (_, assignments) -> displayProductName(assignments.first().productName) }
                ?: emptyList()

            // 检测产品变更：产品变了就取消固定列
            val changedSlots = mutableSetOf<Int>()
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < selectedProductNames.size && slotIndex < lastProductKeys.size) {
                    if (!selectedProductNames[slotIndex].equals(lastProductKeys[slotIndex], ignoreCase = true)) {
                        changedSlots.add(slotIndex)
                    }
                } else if (slotIndex >= selectedProductNames.size) {
                    changedSlots.add(slotIndex)
                }
            }
            if (changedSlots.isNotEmpty()) {
                addLog("检测到产品变更，取消固定列: $changedSlots")
                for (slot in changedSlots) {
                    fixedSlotSet.remove(slot)
                }
                _fixedInputSlots.value = fixedSlotSet
                saveFixedInputSlots(fixedSlotSet)
            }

            // ===== 第二步：收集固定列人员 =====
            val fixedColumnPersons = mutableMapOf<String, String>() // productKey:processName -> personName
            if (fixedSlotSet.isNotEmpty() && lastResult != null) {
                for (slotIndex in fixedSlotSet) {
                    if (slotIndex < selectedProductNames.size) {
                        val productName = selectedProductNames[slotIndex]
                        val productKey = inputProductKey(slotIndex, productName)
                        // 从上次排工结果中读取该槽位的人员
                        val lastAssignments = lastResult.assignments.filter {
                            it.inputIndex == slotIndex
                        }
                        for (a in lastAssignments) {
                            if (!a.assignedPerson.isNullOrBlank()) {
                                fixedColumnPersons["$productKey:${a.processName}"] = a.assignedPerson
                            }
                        }
                    }
                }
                addLog("固定列人员: ${fixedColumnPersons.size}人")
            }

            // 从固定列人员映射构建固定人员集合（用于从可分配池中排除）
            val fixedPeople = fixedColumnPersons.values.toSet()

            // 固定列槽位索引 → 产品key的映射
            val productKeys = selectedProductNames.mapIndexedNotNull { slotIndex, name ->
                val key = inputProductKey(slotIndex, name)
                if (productMap.containsKey(key)) key else null
            }
            val fixedProductKeys = mutableSetOf<String>()
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    fixedProductKeys.add(productKeys[slotIndex])
                }
            }
            // 标记固定列产品的 isFixed
            for (key in fixedProductKeys) {
                productMap[key] = productMap[key]!!.copy(isFixed = true)
            }
            addLog("固定列槽位: ${fixedSlotSet.joinToString(", ")}, 固定产品: ${fixedProductKeys.joinToString(", ")}")

            // 读取固定单元格
            val fixedCellList = allFixedCells.value
            val fixedCellMap = fixedCellList.associate { Pair(it.rowIndex, it.colIndex) to it.personName }
            if (fixedCellMap.isNotEmpty()) {
                addLog("固定单元格: ${fixedCellMap.size}条")
            }

            // ===== 第三步：执行排工 =====
            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames, fixedPeople, fixedColumnPersons, fixedSlotSet, fixedCellMap)
            }

            // ===== 第四步：保存结果 =====
            _dispatchResult.value = result
            saveDispatchResult(result)

            // 更新固定列人员缓存
            val newFixedPeople = result.assignments
                .filter { assignment -> productKeys.getOrNull(assignment.inputIndex)?.let { productMap[it]?.isFixed == true } == true }
                .mapNotNull { it.assignedPerson }
                .toSet()
            _fixedPeople.value = newFixedPeople

            // 更新固定单元格（每次排工后更新为最新人员）
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    val colIndex = slotIndex * 2 + 1
                    val cells = result.assignments
                        .filter { it.inputIndex == slotIndex && !it.assignedPerson.isNullOrBlank() }
                        .map { FixedCell(colIndex = colIndex, rowIndex = it.rowIndex, personName = it.assignedPerson!!) }
                    if (cells.isNotEmpty()) {
                        repo.saveFixedCells(colIndex, cells)
                    }
                }
            }

            addLog("✅ 排工完成！分配${result.assignedCount}人, 固定列${newFixedPeople.size}人, ${result.statusMessage}")
            // 保存最近使用的产品
            selectedProductNames.forEach { name ->
                userPrefs.addRecentProduct(name)
            }
        } catch (e: Exception) { addLog("❌ 错误: ${e.message}") }
        _isLoading.value = false
    }

    fun executeDispatch(selectedProductNames: List<String> = emptyList()) = viewModelScope.launch {
        executeDispatchInternal(selectedProductNames)
    }

    fun importFromExcel(uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始导入Excel...")
        try {
            val ctx = getApplication<Application>()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val engine = DispatchEngine()
                val (success, error) = withContext(Dispatchers.IO) { engine.loadFromExcel(input) }
                if (success) {
                    val data = engine.getParsedData()
                    val r = (ctx as DispatchApplication).repository
                    withContext(Dispatchers.IO) {
                        r.clearAll()
                        for ((name, employeeId) in data.peopleWithIds) { r.addPerson(name, employeeId) }
                        val allP = r.allPersons.first()
                        for (person in allP) { if (person.name in data.leaveList) { r.updatePerson(person.copy(onLeave = true)) } }
                        val updatedP = r.allPersons.first()
                        // 构建工序名到sortOrder的映射
                        val processOrderMap = data.processNames.withIndex().associate { it.value to it.index }
                        for (person in updatedP) {
                            val scores = data.skillScores[person.name] ?: continue
                            for ((processName, score) in scores) {
                                val order = processOrderMap[processName] ?: 0
                                r.setSkillScore(person.id, processName, score, order)
                            }
                        }
                        for ((name, product) in data.products) {
                            val pid = r.addProduct(name, product.capacity, product.requiredPeople)
                            for ((offset, processName) in product.processes.withIndex()) { r.addProcess(pid.toInt(), processName, offset) }
                        }
                    }
                    addLog("✅ 导入成功: ${data.people.size}人, ${data.products.size}个产品")
                    Toast.makeText(ctx, "导入成功: ${data.people.size}人, ${data.products.size}个产品", Toast.LENGTH_SHORT).show()
                } else { addLog("❌ $error"); Toast.makeText(ctx, error, Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) { addLog("❌ ${e.message}") }
        _isLoading.value = false
    }

    fun exportToExcel(uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始导出Excel...")
        val ctx = getApplication<Application>()
        try {
            val r = (ctx as DispatchApplication).repository
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                withContext(Dispatchers.IO) {
                    val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()

                    // 1. 工序评分表
                    val skillSheet = workbook.createSheet("工序评分")
                    val persons = r.allPersons.first()
                    val processNames = r.allProcessNames.first()

                    // 表头: 姓名, 工号, 工序1, 工序2, ...
                    val skillHeader = skillSheet.createRow(0)
                    skillHeader.createCell(0).setCellValue("姓名")
                    skillHeader.createCell(1).setCellValue("工号")
                    processNames.forEachIndexed { index, name ->
                        skillHeader.createCell(index + 2).setCellValue(name)
                    }

                    // 数据行
                    persons.forEachIndexed { rowIndex, person ->
                        val row = skillSheet.createRow(rowIndex + 1)
                        row.createCell(0).setCellValue(person.name)
                        row.createCell(1).setCellValue(person.employeeId)
                        val scores = r.getScoresByPerson(person.id)
                        val scoreMap = scores.associate { it.processName to it.score }
                        processNames.forEachIndexed { colIndex, processName ->
                            row.createCell(colIndex + 2).setCellValue(scoreMap[processName]?.toDouble() ?: 0.0)
                        }
                    }

                    // 2. 工序流程表
                    val processSheet = workbook.createSheet("工序流程")
                    val products = r.allProducts.first()
                    val processHeader = processSheet.createRow(0)
                    processHeader.createCell(0).setCellValue("型号名称")
                    processHeader.createCell(1).setCellValue("产能")
                    processHeader.createCell(2).setCellValue("需求人数")
                    processHeader.createCell(3).setCellValue("工序1")
                    processHeader.createCell(4).setCellValue("工序2")
                    processHeader.createCell(5).setCellValue("工序3")
                    processHeader.createCell(6).setCellValue("工序4")
                    processHeader.createCell(7).setCellValue("工序5")
                    processHeader.createCell(8).setCellValue("工序6")
                    processHeader.createCell(9).setCellValue("工序7")
                    processHeader.createCell(10).setCellValue("工序8")

                    products.forEachIndexed { rowIndex, product ->
                        val row = processSheet.createRow(rowIndex + 1)
                        row.createCell(0).setCellValue(product.name)
                        row.createCell(1).setCellValue(product.capacity.toDouble())
                        row.createCell(2).setCellValue(product.requiredPeople.toDouble())
                        val processes = r.getProcessesOnce(product.id).sortedBy { it.sortOrder }
                        processes.forEachIndexed { colIndex, process ->
                            row.createCell(3 + colIndex).setCellValue(process.processName)
                        }
                    }

                    // 3. 智能排工主表
                    val mainSheet = workbook.createSheet("智能排工")

                    val result = _dispatchResult.value
                    if (result != null) {
                        // 按产品分组收集数据
                        val productData = mutableMapOf<String, MutableList<Pair<String, String>>>()
                        for (a in result.assignments) {
                            if (a.assignedPerson != null) {
                                productData.getOrPut(a.productName) { mutableListOf() }
                                    .add(Pair(a.assignedPerson, a.processName))
                            }
                        }

                        val sortedProducts = if (products.isNotEmpty()) {
                            products.sortedBy { it.name }.map { it.name }
                        } else {
                            productData.keys.sorted()
                        }
                        val maxDataRows = productData.values.maxOfOrNull { it.size } ?: 0

                        // 第1行：请假人员 + 产品名
                        val row1 = mainSheet.createRow(0)
                        row1.createCell(0).setCellValue("请假人员")
                        sortedProducts.forEachIndexed { idx, productName ->
                            val startCol = idx * 2 + 1
                            row1.createCell(startCol).setCellValue(productName)
                        }

                        // 第2行：产能、人数
                        val row2 = mainSheet.createRow(1)
                        sortedProducts.forEachIndexed { idx, productName ->
                            val startCol = idx * 2 + 1
                            val product = products.find { it.name == productName }
                            row2.createCell(startCol).setCellValue(product?.capacity?.toString() ?: "")
                            row2.createCell(startCol + 1).setCellValue(product?.requiredPeople?.toString() ?: "")
                        }

                        // 第3行起：工序、人员
                        val leavePersons = persons.filter { it.onLeave }
                        val totalRows = maxOf(leavePersons.size, maxDataRows)

                        for (i in 0 until totalRows) {
                            val dataRow = mainSheet.createRow(i + 2)

                            // A列：请假人员
                            if (i < leavePersons.size) {
                                dataRow.createCell(0).setCellValue(leavePersons[i].name)
                            }

                            // 各产品数据
                            sortedProducts.forEachIndexed { idx, productName ->
                                val startCol = idx * 2 + 1
                                val dataList = productData[productName] ?: emptyList()
                                if (i < dataList.size) {
                                    val (person, process) = dataList[i]
                                    dataRow.createCell(startCol).setCellValue(process)
                                    dataRow.createCell(startCol + 1).setCellValue(person)
                                }
                            }
                        }
                    } else {
                        val headerRow = mainSheet.createRow(0)
                        headerRow.createCell(0).setCellValue("请假人员")
                        val leavePersons = persons.filter { it.onLeave }
                        leavePersons.forEachIndexed { idx, person ->
                            mainSheet.createRow(idx + 1).createCell(0).setCellValue(person.name)
                        }
                    }

                    workbook.write(output)
                    workbook.close()
                }
                addLog("✅ 导出成功")
                Toast.makeText(ctx, "导出成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            addLog("❌ 导出失败: ${e.message}")
            Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        _isLoading.value = false
    }

    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
        _scoreVersion.update { it + 1 }
    }

    fun updateProcessName(productId: Int, processId: Int, newName: String) = viewModelScope.launch {
        val existing = repo.getProcessesOnce(productId).find { it.id == processId }
        if (existing != null) {
            repo.updateProcess(existing.copy(processName = newName))
            _processVersion.value++
        }
    }

    fun deleteProcess(process: ProductProcess) = viewModelScope.launch {
        repo.deleteProcess(process)
        _processVersion.value++
    }

    fun addProcessToProduct(productId: Int, processName: String) = viewModelScope.launch {
        val processes = repo.getProcessesOnce(productId)
        repo.addProcess(productId, processName, processes.size)
        _processVersion.value++
    }

    fun refreshProcessVersion() {
        _processVersion.value++
    }

    // 同名产品多次输入时，用列索引+同名序号生成唯一 key
    private fun inputProductKey(slotIndex: Int, name: String): String = "$slotIndex:${name.trim()}"

    private fun resultAssignmentsByInput(result: DispatchResult?, inputNames: List<String>, products: List<com.example.smartdispatch.data.entity.Product>): Map<Int, List<ProcessAssignment>> {
        val r = result ?: return emptyMap()
        val productNameMap = products.associateBy { it.name.trim().lowercase() }
        val buckets = r.assignments.groupBy { if (it.inputIndex >= 0) it.inputIndex else (it.columnIndex - 1) / 2 }
        return inputNames.withIndex().mapNotNull { (index, name) ->
            val expectedName = productNameMap[name.trim().lowercase()]?.name ?: return@mapNotNull null
            val matched = buckets[index].orEmpty().filter { it.productName.substringAfter(":") == expectedName }
            if (matched.isEmpty()) null else index to matched
        }.toMap()
    }

    fun assignmentsByInput(result: DispatchResult?, inputNames: List<String>, products: List<com.example.smartdispatch.data.entity.Product>): Map<Int, List<ProcessAssignment>> {
        return resultAssignmentsByInput(result, inputNames, products)
    }

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch { repo.deleteProduct(product) }
    fun toggleProductFixed(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch {
        repo.updateProduct(product.copy(isFixed = !product.isFixed))
    }

    fun deleteProcessFromProduct(process: ProductProcess) = viewModelScope.launch { repo.deleteProcess(process) }

    // 人员管理
    fun addPerson(name: String, employeeId: String = "", jobType: String = "") = viewModelScope.launch {
        repo.addPerson(name, employeeId, jobType)
    }
    fun updatePerson(person: Person) = viewModelScope.launch { repo.updatePerson(person) }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }
    fun insertPersonBefore(beforePerson: Person, name: String, employeeId: String = "", jobType: String = "") = viewModelScope.launch {
        repo.insertPersonBefore(beforePerson, name, employeeId, jobType)
    }
    fun getPersonById(id: Int): Person? = runBlockingOnMain { repo.getPersonById(id) }

    // 获取所有评分（一次性）
    fun getAllScoreProcessesOnce(): List<String> = runBlockingOnMain { repo.allProcessNames.first() }

    // 获取所有产品（一次性）
    fun getAllProductsOnce(): List<com.example.smartdispatch.data.entity.Product> = runBlockingOnMain { repo.allProducts.first() }

    // 获取所有人员（一次性）
    fun getAllPersonsOnce(): List<Person> = runBlockingOnMain { repo.allPersons.first() }

    // 清空所有数据
    fun clearAllData() = viewModelScope.launch {
        repo.clearAll()
        addLog("已清空所有数据")
    }

    // 辅助：在主线程上运行挂起函数并返回结果
    private fun <T> runBlockingOnMain(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking {
            block()
        }
    }

    fun clearFocus() { _focusedInputIndex.value = -1 }

    fun selectProduct(index: Int, productName: String) {
        val oldList = _inputNames.value
        val newList = oldList.toMutableList().apply { set(index, productName) }
        saveInputNames(newList)
        _inputNames.value = newList
        _focusedInputIndex.value = -1 // 关闭下拉列表
    }

    fun selectProductAndDispatch(index: Int, productName: String) {
        selectProduct(index, productName)
        autoDispatch()
    }

    fun toggleLeave(person: Person) = viewModelScope.launch {
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
    }

    fun toggleLeaveAndDispatch(person: Person) = viewModelScope.launch {
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
        autoDispatch()
    }

    fun updatePersonInfo(person: Person, name: String, employeeId: String, jobType: String) = viewModelScope.launch {
        repo.updatePerson(person.copy(name = name, employeeId = employeeId, jobType = jobType))
    }

    // 自动执行排工（当输入框变化时调用）
    fun autoDispatch() {
        viewModelScope.launch {
            val names = _inputNames.value.mapNotNull { name ->
                if (name.isNotBlank()) {
                    allProducts.first().find { it.name.equals(name.trim(), ignoreCase = true) }?.name
                } else {
                    null
                }
            }
            if (names.isNotEmpty()) {
                executeDispatchInternal(names)
            }
        }
    }
}