package com.example.smartdispatch

import android.app.Application
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DispatchApplication
    val repo = app.repository
    val userPrefs = app.userPrefs
    private val prefs = application.getSharedPreferences("smart_dispatch_prefs", Context.MODE_PRIVATE)

    // ===== 显示设置 =====
    var fontSize by mutableStateOf(prefs.getFloat("fontSize", 11f))
        private set
    var rowHeight by mutableStateOf(prefs.getInt("rowHeight", 28))
        private set
    var colWidth by mutableStateOf(prefs.getInt("colWidth", 80))
        private set

    fun adjustFontSize(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(8f, 20f)
        prefs.edit().putFloat("fontSize", fontSize).apply()
    }
    fun adjustRowHeight(delta: Float) {
        rowHeight = (rowHeight + delta.toInt()).coerceIn(20, 60)
        prefs.edit().putInt("rowHeight", rowHeight).apply()
    }
    fun adjustColWidth(delta: Float) {
        colWidth = (colWidth + delta.toInt()).coerceIn(50, 200)
        prefs.edit().putInt("colWidth", colWidth).apply()
    }

    // ===== 固定单元格 =====
    fun saveFixedCells(colIndex: Int, cells: List<FixedCell>) = viewModelScope.launch {
        repo.saveFixedCells(colIndex, cells)
    }
    fun deleteFixedCellsByColumn(colIndex: Int) = viewModelScope.launch {
        repo.deleteFixedCellsByColumn(colIndex)
    }

    // ===== 工序评分管理 =====
    fun deleteScoreProcess(processName: String) = viewModelScope.launch {
        repo.deleteScoreProcess(processName)
        _scoreVersion.update { it + 1 }
    }
    fun renameScoreProcess(oldName: String, newName: String) = viewModelScope.launch {
        repo.renameScoreProcess(oldName, newName)
        _scoreVersion.update { it + 1 }
    }
    fun addScoreProcess(processName: String, beforeProcess: String? = null) = viewModelScope.launch {
        val processes = repo.getAllScoreProcessesOnce()
        val maxOrder = processes.maxOfOrNull { it.sortOrder } ?: -1
        val newOrder = if (beforeProcess != null) {
            val before = processes.find { it.processName == beforeProcess }
            before?.sortOrder ?: (maxOrder + 1)
        } else {
            maxOrder + 1
        }
        repo.addScoreProcess(processName, newOrder)
        _scoreVersion.update { it + 1 }
    }

    // ===== 固定输入槽位 =====
    private val _fixedInputSlots = MutableStateFlow(loadFixedInputSlots())
    val fixedInputSlots: StateFlow<Set<Int>> = _fixedInputSlots.asStateFlow()

    private fun loadFixedInputSlots(): Set<Int> {
        return prefs.getStringSet("fixed_slots", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }
    private fun saveFixedInputSlots(slots: Set<Int>) {
        prefs.edit().putStringSet("fixed_slots", slots.map { it.toString() }.toSet()).apply()
    }
    fun toggleFixedSlot(index: Int) {
        val current = _fixedInputSlots.value.toMutableSet()
        if (index in current) current.remove(index) else current.add(index)
        _fixedInputSlots.value = current
        saveFixedInputSlots(current)
    }
    fun isInputSlotFixed(index: Int): Boolean = index in _fixedInputSlots.value

    // ===== 输入框名称 =====
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
    fun updateInputName(index: Int, value: String) {
        val oldList = _inputNames.value
        val newList = oldList.toMutableList().apply { set(index, value) }
        saveInputNames(newList)
        _inputNames.value = newList
    }

    // ===== 输入框焦点 =====
    private val _focusedInputIndex = MutableStateFlow(-1)
    val focusedInputIndex: StateFlow<Int> = _focusedInputIndex.asStateFlow()
    fun setFocusedInput(index: Int) { _focusedInputIndex.value = index }
    fun clearFocus() { _focusedInputIndex.value = -1 }

    // ===== 产品匹配（自动完成） =====
    val matchedProducts: StateFlow<List<String>> = combine(_inputNames, _focusedInputIndex, allProducts, recentProducts) { names, focusIndex, products, recent ->
        if (focusIndex < 0 || focusIndex >= names.size) emptyList()
        else {
            val text = names[focusIndex].trim()
            if (text.isEmpty()) {
                recent.take(10)
            } else {
                val matched = products.filter { it.name.contains(text, ignoreCase = true) }.map { it.name }
                matched.sortedWith(compareBy<String> { productName ->
                    when {
                        productName.equals(text, ignoreCase = true) -> 0
                        productName.startsWith(text, ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy { productName ->
                    val recentIndex = recent.indexOf(productName)
                    if (recentIndex >= 0) recentIndex else Int.MAX_VALUE
                }).take(10)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())

    fun selectProduct(index: Int, productName: String) {
        val oldList = _inputNames.value
        val newList = oldList.toMutableList().apply { set(index, productName) }
        saveInputNames(newList)
        _inputNames.value = newList
        _focusedInputIndex.value = -1
    }
    fun selectProductAndDispatch(index: Int, productName: String) {
        selectProduct(index, productName)
        autoDispatch()
    }

    // ===== 日志 =====
    private val _logs = MutableStateFlow(emptyList<String>())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }

    // ===== 人员管理 =====
    fun addPerson(name: String, employeeId: String = "", jobType: String = "") = viewModelScope.launch { repo.addPerson(name, employeeId, jobType) }
    fun toggleLeave(person: Person) = viewModelScope.launch {
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
    }
    fun toggleLeaveAndDispatch(person: Person) = viewModelScope.launch {
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
        autoDispatch()
    }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }
    fun updatePersonInfo(person: Person, name: String, employeeId: String, jobType: String) = viewModelScope.launch {
        repo.updatePerson(person.copy(name = name, employeeId = employeeId, jobType = jobType))
    }
    fun insertPersonBefore(beforePerson: Person, name: String, employeeId: String = "", jobType: String = "") = viewModelScope.launch {
        repo.insertPersonBefore(beforePerson, name, employeeId, jobType)
    }

    // ===== 排工 =====
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

    // ===== 技能评分 =====
    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
        _scoreVersion.update { it + 1 }
    }

    // ===== 工序管理 =====
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

    // ===== 排工结果按输入槽位分组 =====
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

    // ===== 产品管理 =====
    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch { repo.deleteProduct(product) }
    fun toggleProductFixed(product: com.example.smartdispatch.data.entity.Product) = viewModelScope.launch {
        repo.updateProduct(product.copy(isFixed = !product.isFixed))
    }
    fun deleteProcessFromProduct(process: ProductProcess) = viewModelScope.launch { repo.deleteProcess(process) }

    // ===== 核心排工逻辑 =====
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

            // 检测固定列
            val fixedSlotSet = _fixedInputSlots.value.toMutableSet()
            val lastResult = _dispatchResult.value
            fun displayProductName(rawName: String): String = rawName.substringAfter(":")
            val lastProductKeys = lastResult?.assignments
                ?.groupBy { assignment -> if (assignment.inputIndex >= 0) assignment.inputIndex else (assignment.columnIndex - 1) / 2 }
                ?.toSortedMap()
                ?.map { (_, assignments) -> displayProductName(assignments.first().productName) }
                ?: emptyList()

            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                var cancelled = 0
                for (slotIndex in fixedSlotSet.toList()) {
                    if (slotIndex < lastProductKeys.size && slotIndex < selectedProductNames.size) {
                        val lastProduct = lastProductKeys[slotIndex]
                        val currentProduct = selectedProductNames[slotIndex]
                        if (lastProduct != currentProduct) {
                            fixedSlotSet.remove(slotIndex)
                            cancelled++
                        }
                    }
                }
                if (cancelled > 0) {
                    _fixedInputSlots.value = fixedSlotSet.toSet()
                    saveFixedInputSlots(fixedSlotSet.toSet())
                    addLog("固定列: $cancelled 个槽位产品变更，自动取消固定")
                }
            }

            val fixedColumnPersons = mutableMapOf<String, String>()
            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                for (slotIndex in fixedSlotSet) {
                    if (slotIndex < lastProductKeys.size) {
                        val productName = lastProductKeys[slotIndex]
                        lastResult.assignments
                            .filter { assignment ->
                                assignment.assignedPerson != null &&
                                    ((assignment.inputIndex == slotIndex) ||
                                        (assignment.inputIndex < 0 && displayProductName(assignment.productName) == productName))
                            }
                            .forEach { fixedColumnPersons["${slotIndex}_${it.rowIndex}"] = it.assignedPerson!! }
                    }
                }
                addLog("固定列人员: ${fixedColumnPersons.size}个已读取")
            }

            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            addLog("评分人数: ${scoreMap.size}, 工序优先级数: ${processNames.size}")

            engine.setSkillScoresData(scoreMap)
            val fixedPeople = fixedColumnPersons.values.toSet()

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
            for (key in fixedProductKeys) {
                productMap[key] = productMap[key]!!.copy(isFixed = true)
            }
            addLog("固定列槽位: ${fixedSlotSet.joinToString(", ")}, 固定产品: ${fixedProductKeys.joinToString(", ")}")

            val fixedCellList = allFixedCells.value
            val fixedCellMap = fixedCellList.associate { Pair(it.rowIndex, it.colIndex) to it.personName }
            if (fixedCellMap.isNotEmpty()) {
                addLog("固定单元格: ${fixedCellMap.size}条")
            }

            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames, fixedPeople, fixedColumnPersons, fixedSlotSet, fixedCellMap)
            }
            _dispatchResult.value = result
            saveDispatchResult(result)
            val newFixedPeople = result.assignments
                .filter { assignment -> productKeys.getOrNull(assignment.inputIndex)?.let { productMap[it]?.isFixed == true } == true }
                .mapNotNull { it.assignedPerson }
                .toSet()
            _fixedPeople.value = newFixedPeople

            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    val productName = productKeys[slotIndex]
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
            selectedProductNames.forEach { name ->
                userPrefs.addRecentProduct(name)
            }
        } catch (e: Exception) { addLog("❌ 错误: ${e.message}") }
        _isLoading.value = false
    }

    fun executeDispatch(selectedProductNames: List<String> = emptyList()) = viewModelScope.launch {
        executeDispatchInternal(selectedProductNames)
    }

    // ===== Excel 导入导出 =====
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
                    r.clearAllData()
                    data.products.forEach { r.addProduct(it.name, it.capacity, it.requiredPeople) }
                    val productMap = r.getAllProductsOnce().associateBy { it.name }
                    data.persons.forEach { r.addPerson(it.name, it.employeeId, it.jobType) }
                    val personMap = r.getAllPersonsOnce().associateBy { it.name }
                    data.skillScores.forEach { (personName, processName, score) ->
                        val person = personMap[personName]
                        val product = productMap.values.firstOrNull()
                        if (person != null && product != null) {
                            r.setSkillScore(person.id, processName, score)
                        }
                    }
                    data.processes.forEach { (productName, processName, sortOrder) ->
                        val product = productMap[productName]
                        if (product != null) {
                            r.addProcess(product.id, processName, sortOrder)
                        }
                    }
                    addLog("✅ 导入完成: ${data.products.size}产品, ${data.persons.size}人, ${data.skillScores.size}评分, ${data.processes.size}工序")
                } else {
                    addLog("❌ 导入失败: $error")
                }
            }
        } catch (e: Exception) { addLog("❌ 导入错误: ${e.message}") }
        _isLoading.value = false
    }

    fun exportToExcel(uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始导出Excel...")
        try {
            val ctx = getApplication<Application>()
            val persons = allPersons.first()
            val products = allProducts.first()
            val allProcessNamesList = allProcessNames.first()
            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            val productProcessMap = mutableMapOf<String, List<String>>()
            for (product in products) {
                val processes = repo.getProcessesOnce(product.id)
                productProcessMap[product.name] = processes.map { it.processName }
            }
            val engine = DispatchEngine()
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    engine.exportToExcel(output, persons, products, scoreMap, productProcessMap, allProcessNamesList)
                }
            }
            addLog("✅ 导出完成")
        } catch (e: Exception) { addLog("❌ 导出错误: ${e.message}") }
        _isLoading.value = false
    }

    // ===== 排工结果持久化 =====
    private val _dispatchResult = MutableStateFlow(loadDispatchResult())
    val dispatchResult: StateFlow<DispatchResult?> = _dispatchResult.asStateFlow()

    private fun loadDispatchResult(): DispatchResult? {
        val json = prefs.getString("dispatch_result", null) ?: return null
        return try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<DispatchResult>(json)
        } catch (e: Exception) { null }
    }
    private fun saveDispatchResult(result: DispatchResult) {
        try {
            val json = kotlinx.serialization.json.Json { prettyPrint = false }.encodeToString(result)
            prefs.edit().putString("dispatch_result", json).apply()
        } catch (e: Exception) { addLog("保存结果失败: ${e.message}") }
    }

    // ===== 数据库 Flow 暴露 =====
    val allPersons: StateFlow<List<Person>> = repo.allPersons.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())
    val allProducts: StateFlow<List<com.example.smartdispatch.data.entity.Product>> = repo.allProducts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())
    val allProcessNames: StateFlow<List<String>> = repo.allProcessNames.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())
    val allFixedCells: StateFlow<List<FixedCell>> = repo.allFixedCells.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())
    val recentProducts: StateFlow<List<String>> = userPrefs.recentProducts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), emptyList())

    // ===== 版本号（用于触发刷新） =====
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()

    private val _processVersion = MutableStateFlow(0)
    val processVersion: StateFlow<Int> = _processVersion.asStateFlow()

    // ===== 加载状态 =====
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ===== 固定人员集合 =====
    private val _fixedPeople = MutableStateFlow(emptySet<String>())
    val fixedPeople: StateFlow<Set<String>> = _fixedPeople.asStateFlow()
}