package com.example.smartdispatch

import com.example.smartdispatch.BuildConfig
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartdispatch.data.AppDatabase
import com.example.smartdispatch.data.DispatchRepository
import com.example.smartdispatch.data.UserPreferences
import com.example.smartdispatch.data.entity.*
import com.example.smartdispatch.engine.DispatchEngine
import com.example.smartdispatch.model.DispatchResult
import com.example.smartdispatch.model.ProcessAssignment
import com.example.smartdispatch.ui.theme.智能排工Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class DispatchApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { DispatchRepository(
        database.personDao(), database.skillScoreDao(),
        database.productDao(), database.productProcessDao(), database.assignmentDao(),
        database.fixedCellDao()
    )}
    val userPreferences by lazy { UserPreferences(this) }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as DispatchApplication).repository
    private val userPrefs = (application as DispatchApplication).userPreferences
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
    // 固定列人员缓存（上次排工中被分配到固定列产品的人员）
    private val _fixedPeople = MutableStateFlow<Set<String>>(emptySet())
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
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()
    private val _processVersion = MutableStateFlow(0)
    val processVersion: StateFlow<Int> = _processVersion.asStateFlow()
    
    // 智能排工页输入框状态（持久化到SharedPreferences）
    private val _inputNames = MutableStateFlow(loadInputNames())
    val inputNames: StateFlow<List<String>> = _inputNames.asStateFlow()
    
    private fun loadInputNames(): List<String> {
        return (1..6).map { i -> prefs.getString("input_$i", "") ?: "" }
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
        // 名称变更时的固定列取消在排工前检测
    }
    
    // 当前正在编辑的输入框索引
    private val _focusedInputIndex = MutableStateFlow(-1)
    val focusedInputIndex: StateFlow<Int> = _focusedInputIndex.asStateFlow()
    fun setFocusedInput(index: Int) { _focusedInputIndex.value = index }
    fun clearFocus() { _focusedInputIndex.value = -1 }
    
    // 匹配的型号名称列表（用于自动完成，精确匹配优先，然后按匹配位置排序）
    val matchedProducts: StateFlow<List<String>> = combine(_inputNames, _focusedInputIndex, allProducts, recentProducts) { names, focusIndex, products, recent ->
        if (focusIndex < 0 || focusIndex >= names.size) emptyList()
        else {
            val text = names[focusIndex].trim()
            if (text.isEmpty()) {
                // 输入为空时显示最近使用的产品（最多10个）
                recent.take(10)
            } else {
                val matched = products.filter { it.name.contains(text, ignoreCase = true) }.map { it.name }
                // 排序：精确匹配最前 → 以输入开头 → 包含输入 → 最近使用
                matched.sortedWith(compareBy<String> { productName ->
                    when {
                        productName.equals(text, ignoreCase = true) -> 0  // 精确匹配
                        productName.startsWith(text, ignoreCase = true) -> 1  // 以输入开头
                        else -> 2  // 包含输入
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
        _focusedInputIndex.value = -1 // 关闭下拉列表
    }
    
    fun selectProductAndDispatch(index: Int, productName: String) {
        selectProduct(index, productName)
        autoDispatch()
    }

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }
    fun addPerson(name: String, employeeId: String = "") = viewModelScope.launch { repo.addPerson(name, employeeId) }
    fun toggleLeave(person: Person) = viewModelScope.launch { 
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
    }
    fun toggleLeaveAndDispatch(person: Person) = viewModelScope.launch { 
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
        autoDispatch()
    }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }
    
    // 自动执行排工（当输入框变化时调用）
    fun autoDispatch() {
        viewModelScope.launch {
            // 保留重复的型号名称，支持同一型号多实例（如两条产线）
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
        // 获取当前最大排序号
        val processes = repo.getProcessesOnce(productId)
        repo.addProcess(productId, processName, processes.size)
        _processVersion.value++
    }

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { repo.deleteProduct(product) }
    fun toggleProductFixed(product: Product) = viewModelScope.launch { 
        repo.updateProduct(product.copy(isFixed = !product.isFixed))
    }

    fun deleteProcessFromProduct(process: ProductProcess) = viewModelScope.launch { repo.deleteProcess(process) }
    
    private suspend fun executeDispatchInternal(selectedProductNames: List<String>) {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val engine = DispatchEngine()
            val persons = allPersons.first()
            val allProductsList = allProducts.first()
            val processNames = allProcessNames.first()
            val peopleNames = persons.map { it.name }
            val leaveNames = persons.filter { it.onLeave }.map { it.name }

            // ===== 第一步：检测固定列，从上次排工结果中读取人员 =====
            val fixedSlotSet = _fixedInputSlots.value.toMutableSet()
            val lastResult = _dispatchResult.value
            val lastProductKeys = lastResult?.assignments?.mapNotNull { it.productName }?.distinct() ?: emptyList()
            
            // 检测产品变更：产品变了就取消固定列
            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                var cancelled = 0
                for (slotIndex in fixedSlotSet.toList()) {
                    if (slotIndex < lastProductKeys.size && slotIndex < selectedProductNames.size) {
                        val lastProduct = lastProductKeys[slotIndex].substringBefore("@")
                        val currentProduct = selectedProductNames[slotIndex]
                        if (lastProduct != currentProduct) {
                            // 产品变了，取消固定
                            fixedSlotSet.remove(slotIndex)
                            cancelled++
                        }
                    }
                }
                if (cancelled > 0) {
                    addLog("固定列: $cancelled 个槽位产品变更，自动取消固定")
                }
            }
            
            // 从上次排工结果中读取固定列的人员
            val fixedColumnPersons = mutableMapOf<String, String>() // key=槽位索引_行号, value=人员名
            if (lastResult != null && fixedSlotSet.isNotEmpty()) {
                for (slotIndex in fixedSlotSet) {
                    if (slotIndex < lastProductKeys.size) {
                        val productKey = lastProductKeys[slotIndex]
                        lastResult.assignments
                            .filter { it.productName == productKey && it.assignedPerson != null }
                            .forEach { fixedColumnPersons["${slotIndex}_${it.rowIndex}"] = it.assignedPerson!! }
                    }
                }
                addLog("固定列人员: ${fixedColumnPersons.size}个已读取")
            }

            // 用带索引的key区分相同名称的产品实例（如 "G32705@0", "G32705@1"）
            val productMap = mutableMapOf<String, com.example.smartdispatch.model.Product>()
            val nameCount = mutableMapOf<String, Int>()
            for (name in selectedProductNames) {
                val product = allProductsList.find { it.name == name }
                if (product != null) {
                    val count = nameCount.getOrDefault(name, 0)
                    val uniqueKey = "${name}@$count"
                    nameCount[name] = count + 1
                    val processes = repo.getProcessesOnce(product.id)
                    productMap[uniqueKey] = com.example.smartdispatch.model.Product(
                        name, product.capacity, product.requiredPeople,
                        processes.map { it.processName }, product.isFixed
                    )
                }
            }
            addLog("排工产品数: ${productMap.size}")

            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            addLog("评分人数: ${scoreMap.size}, 工序优先级数: ${processNames.size}")

            engine.setSkillScoresData(scoreMap)
            // 从固定列人员映射构建固定人员集合（用于从可分配池中排除）
            val fixedPeople = fixedColumnPersons.values.toSet()

            // 固定列槽位索引 → 产品key的映射
            val productKeys = productMap.keys.toList()
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

            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames, fixedPeople, fixedColumnPersons, fixedSlotSet, fixedCellMap)
            }
            _dispatchResult.value = result
            // 更新固定列人员集合（用于显示）
            val newFixedPeople = result.assignments
                .filter { productMap[it.productName]?.isFixed == true }
                .mapNotNull { it.assignedPerson }
                .toSet()
            _fixedPeople.value = newFixedPeople
            
            // 更新固定单元格（每次排工后更新为最新人员）
            for (slotIndex in fixedSlotSet) {
                if (slotIndex < productKeys.size) {
                    val productName = productKeys[slotIndex]
                    val colIndex = slotIndex * 2 + 1
                    val cells = result.assignments
                        .filter { it.productName == productName && !it.assignedPerson.isNullOrBlank() }
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
                        for (person in updatedP) {
                            val scores = data.skillScores[person.name] ?: continue
                            for ((processName, score) in scores) { r.setSkillScore(person.id, processName, score) }
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
                    val processNames = r.allProcessNames.first()  // 保持原始顺序（按id排序）
                    
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
                    
                    // 表头: 产品, 产能, 人数, 工序1, 工序2, ...
                    val processHeader = processSheet.createRow(0)
                    processHeader.createCell(0).setCellValue("产品")
                    processHeader.createCell(1).setCellValue("产能")
                    processHeader.createCell(2).setCellValue("人数")
                    (0..9).forEach { processHeader.createCell(3 + it).setCellValue("工序${it + 1}") }
                    
                    // 数据行
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
                    
                    // 首行：请假人员 + 产品名（每产品占2列：人员、工序）
                    val headerRow = mainSheet.createRow(0)
                    headerRow.createCell(0).setCellValue("请假人员")
                    products.forEachIndexed { index, product ->
                        val col = index * 2 + 1
                        headerRow.createCell(col).setCellValue(product.name)
                        // col+1 留给工序（空列）
                    }
                    
                    // 请假人员列表（从第2行开始）
                    val leavePersons = persons.filter { it.onLeave }
                    leavePersons.forEachIndexed { index, person ->
                        mainSheet.createRow(index + 1).createCell(0).setCellValue(person.name)
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            智能排工Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainScreen() }
            }
        }
    }
}

// ========== 设置页面（菜单入口） ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var showProcessEdit by remember { mutableStateOf(false) }
    var showFixedColumn by remember { mutableStateOf(false) }
    val fontSize by viewModel.fontSize.collectAsState()
    val rowHeight by viewModel.rowHeight.collectAsState()
    val colWidth by viewModel.colWidth.collectAsState()

    if (showProcessEdit) {
        ProcessEditScreen(viewModel = viewModel, onDismiss = { showProcessEdit = false })
    } else if (showFixedColumn) {
        FixedColumnScreen(viewModel = viewModel, onDismiss = { showFixedColumn = false })
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("设置", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 菜单项：编辑工序流程
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showProcessEdit = true }.padding(vertical = 16.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, null, tint = Color(0xFF1976D2), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("编辑工序流程", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("搜索产品，编辑产能、人数和工序列表", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                    }
                    Divider()
                    // 菜单项：固定列
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showFixedColumn = true }.padding(vertical = 16.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFBC02D), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("固定列", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("设置固定列产品，排工时人员留任原岗位", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                    }
                    Divider()
                    // 菜单项：字体大小
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.TextFields, null, tint = Color(0xFF388E3C), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("字体大小: ${fontSize.toInt()}sp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的字体大小", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustFontSize(-1f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${fontSize.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustFontSize(1f) }) { Icon(Icons.Default.Add, null) }
                    }
                    Divider()
                    // 菜单项：行高
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VerticalAlignBottom, null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("行高: ${rowHeight.toInt()}dp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的行高", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustRowHeight(-2f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${rowHeight.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustRowHeight(2f) }) { Icon(Icons.Default.Add, null) }
                    }
                    Divider()
                    // 菜单项：列宽
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ViewColumn, null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("列宽: ${colWidth.toInt()}dp", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("调整排工表格的列宽", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { viewModel.adjustColWidth(-5f) }) { Icon(Icons.Default.Remove, null) }
                        Text("${colWidth.toInt()}", fontSize = 14.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { viewModel.adjustColWidth(5f) }) { Icon(Icons.Default.Add, null) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

// ========== 编辑工序流程页面 ==========
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProcessEditScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val coroutineScope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    
    // 本地编辑状态（不立即保存）
    var editCapacity by remember { mutableStateOf("") }
    var editPeople by remember { mutableStateOf("") }
    var editingProcesses by remember { mutableStateOf<List<ProductProcess>>(emptyList()) }
    var originalProcesses by remember { mutableStateOf<List<ProductProcess>>(emptyList()) }
    var newProcessName by remember { mutableStateOf("") }
    
    // 历史记录（用于撤销）
    var history by remember { mutableStateOf<List<Triple<String, String, List<ProductProcess>>>>(emptyList()) }
    
    // 是否有修改
    val hasChanges = remember(editCapacity, editPeople, editingProcesses, originalProcesses, editingProduct) {
        editingProduct != null && (
            editCapacity != editingProduct!!.capacity.toString() ||
            editPeople != editingProduct!!.requiredPeople.toString() ||
            editingProcesses.map { it.processName } != originalProcesses.map { it.processName }
        )
    }

    val filteredProducts = remember(searchText, products) {
        if (searchText.length < 2) emptyList()
        else products.filter { it.name.contains(searchText.trim(), ignoreCase = true) }.take(30)
    }

    val context = LocalContext.current
    LaunchedEffect(editingProduct) {
        if (editingProduct != null) {
            val processes = repo.getProcessesOnce(editingProduct!!.id).sortedBy { it.sortOrder }
            editingProcesses = processes
            originalProcesses = processes.map { it.copy() }
            editCapacity = editingProduct!!.capacity.toString()
            editPeople = editingProduct!!.requiredPeople.toString()
            history = emptyList()  // 清空历史
            android.widget.Toast.makeText(context, "加载: ${editingProduct!!.name}, ${processes.size}个工序", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    // 保存历史
    fun saveHistory() {
        history = history + Triple(editCapacity, editPeople, editingProcesses.map { it.copy() })
        if (history.size > 20) history = history.drop(1)  // 最多20步
    }
    
    // 撤销
    fun undo() {
        if (history.isNotEmpty()) {
            val last = history.last()
            history = history.dropLast(1)
            editCapacity = last.first
            editPeople = last.second
            editingProcesses = last.third
        }
    }
    
    // 移动工序
    fun moveProcess(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= editingProcesses.size || to >= editingProcesses.size) return
        saveHistory()
        val list = editingProcesses.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        editingProcesses = list.mapIndexed { i, p -> p.copy(sortOrder = i) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("编辑工序流程", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(onClick = { undo() }) {
                        Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("撤销", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(500.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (editingProduct != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        IconButton(onClick = { editingProduct = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp)) }
                        Text(editingProduct!!.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 产能/人数 - 紧凑排列
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        Text("产能:", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        BasicTextField(
                            value = editCapacity, onValueChange = { if (it.all { c -> c.isDigit() }) editCapacity = it },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color.Black),
                            modifier = Modifier.width(50.dp).height(20.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("人数:", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        BasicTextField(
                            value = editPeople, onValueChange = { if (it.all { c -> c.isDigit() }) editPeople = it },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color.Black),
                            modifier = Modifier.width(50.dp).height(20.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black)
                        )
                    }
                    Text("工序列表（长按手柄拖动）:", fontSize = 11.sp, color = Color(0xFF666666))
                    // 记录累计拖动偏移，用于计算目标位置
                    var dragAccumY by remember { mutableStateOf(0f) }
                    var dragFromIndex by remember { mutableStateOf(-1) }
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(editingProcesses.size, key = { editingProcesses[it].id }) { index ->
                                val process = editingProcesses[index]
                                var editName by remember { mutableStateOf(process.processName) }
                                val isDragging = dragFromIndex == index
                                // 拖动时置顶显示
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 100f else 1f)
                                        .offset(y = if (isDragging) dragAccumY.dp else 0.dp)
                                        .shadow(
                                            if (isDragging) 8.dp else 0.dp,
                                            RoundedCornerShape(2.dp)
                                        )
                                        .background(
                                            if (isDragging) Color(0xFFE3F2FD) else Color.White,
                                            RoundedCornerShape(2.dp)
                                        )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().height(26.dp)
                                    ) {
                                        Text("${index + 1}.", fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                                        BasicTextField(
                                            value = editName,
                                            onValueChange = {
                                                editName = it
                                                editingProcesses = editingProcesses.toMutableList().also { list ->
                                                    list[index] = process.copy(processName = it)
                                                }
                                            },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.Black),
                                            modifier = Modifier.width(160.dp).height(24.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                            decorationBox = { innerTextField ->
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                                    innerTextField()
                                                }
                                            }
                                        )
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = {
                                            saveHistory()
                                            editingProcesses = editingProcesses.filterIndexed { i, _ -> i != index }.mapIndexed { i, p -> p.copy(sortOrder = i) }
                                        }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        // 拖动手柄 - 长按触发拖动
                                        Box(
                                            modifier = Modifier
                                                .width(32.dp)
                                                .height(26.dp)
                                                .pointerInput(Unit) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            dragFromIndex = index
                                                            dragAccumY = 0f
                                                        },
                                                        onDragEnd = {
                                                            if (dragFromIndex >= 0) {
                                                                // 拖动超过行高一半才移动，减小步进
                                                                val threshold = 13f
                                                                val moveSteps = (dragAccumY / threshold).toInt()
                                                                if (moveSteps != 0) {
                                                                    val newIndex = (dragFromIndex + moveSteps)
                                                                        .coerceIn(0, editingProcesses.size - 1)
                                                                    if (newIndex != dragFromIndex) {
                                                                        saveHistory()
                                                                        moveProcess(dragFromIndex, newIndex)
                                                                    }
                                                                }
                                                            }
                                                            dragFromIndex = -1
                                                            dragAccumY = 0f
                                                        },
                                                        onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                                            change.consume()
                                                            dragAccumY += dragAmount.y
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.DragHandle, null, tint = Color(0xFF999999), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            // 添加新工序
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(26.dp)) {
                                    Text("${editingProcesses.size + 1}.", fontSize = 11.sp, color = Color(0xFF666666), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                                    BasicTextField(
                                        value = newProcessName, onValueChange = { newProcessName = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.Black),
                                        modifier = Modifier.width(160.dp).height(24.dp).border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                        decorationBox = { innerTextField ->
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                                if (newProcessName.isEmpty()) {
                                                    Text("新工序名称", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                                                }
                                                innerTextField()
                                            }
                                        }
                                    )
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = {
                                        if (newProcessName.isNotBlank()) {
                                            saveHistory()
                                            editingProcesses = editingProcesses + ProductProcess(
                                                productId = editingProduct!!.id,
                                                processName = newProcessName.trim(),
                                                sortOrder = editingProcesses.size
                                            )
                                            newProcessName = ""
                                        }
                                    }, enabled = newProcessName.isNotBlank(), modifier = Modifier.size(26.dp)) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF1976D2), modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Spacer(modifier = Modifier.width(32.dp)) // 占位，对齐手柄位置
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = searchText, onValueChange = { searchText = it },
                        placeholder = { Text("输入型号名称搜索（至少2个字符）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("共${products.size}个产品，已过滤${filteredProducts.size}个", fontSize = 11.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (searchText.length < 2) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                    Text("请输入至少2个字符搜索", color = Color(0xFF999999))
                                }
                            }
                        } else if (filteredProducts.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                    Text("未找到匹配的产品", color = Color(0xFF999999))
                                }
                            }
                        } else {
                            items(filteredProducts) { product ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { editingProduct = product }.padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(product.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("产能:${product.capacity} 人数:${product.requiredPeople}", fontSize = 11.sp, color = Color(0xFF666666))
                                    }
                                    Icon(Icons.Default.Edit, null, tint = Color(0xFF1976D2), modifier = Modifier.size(18.dp))
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (editingProduct != null && hasChanges) {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            // 保存产能/人数
                            viewModel.updateProduct(editingProduct!!.copy(
                                capacity = editCapacity.toIntOrNull() ?: 0,
                                requiredPeople = editPeople.toIntOrNull() ?: 0
                            ))
                            // 删除旧工序，插入新工序
                            repo.deleteProcessesByProduct(editingProduct!!.id)
                            editingProcesses.forEachIndexed { i, p ->
                                repo.addProcess(editingProduct!!.id, p.processName, i)
                            }
                            editingProduct = null
                        }
                    }) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存", color = Color(0xFF2E7D32))
                    }
                }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        }
    )
}

// ========== 固定列设置页面 ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedColumnScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val inputNames by viewModel.inputNames.collectAsState()
    val fixedSlots by viewModel.fixedInputSlots.collectAsState()
    val dispatchResult by viewModel.dispatchResult.collectAsState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固定列", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text("点击 ⭐ 标记固定列，固定列人员排工时留任原岗位", fontSize = 11.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(inputNames.size) { index ->
                        val name = inputNames[index]
                        val isFixed = index in fixedSlots
                        if (name.isBlank()) {
                            // 空槽位
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("第${index + 1}列", fontSize = 13.sp, color = Color(0xFF999999))
                                Spacer(Modifier.weight(1f))
                                Text("(空)", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("第${index + 1}列", fontSize = 11.sp, color = Color(0xFF999999))
                                    Text(name, fontSize = 14.sp, fontWeight = if (isFixed) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    viewModel.toggleFixedSlot(index)
                                    // 同时处理固定单元格
                                    if (!isFixed) {
                                        // 标记固定：从排工结果读取该列人员，保存到 FixedCell
                                        val result = dispatchResult
                                        if (result != null) {
                                            val colIndex = index * 2 + 1
                                            val cells = result.assignments
                                                .filter { it.productName == name && !it.assignedPerson.isNullOrBlank() }
                                                .map { FixedCell(colIndex = colIndex, rowIndex = it.rowIndex, personName = it.assignedPerson!!) }
                                            if (cells.isNotEmpty()) {
                                                viewModel.saveFixedCells(colIndex, cells)
                                            }
                                        }
                                    } else {
                                        // 取消固定：删除该列的 FixedCell
                                        val colIndex = index * 2 + 1
                                        viewModel.deleteFixedCellsByColumn(colIndex)
                                    }
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (isFixed) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isFixed) "取消固定" else "设为固定",
                                        tint = if (isFixed) Color(0xFFFBC02D) else Color(0xFFBDBDBD),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            if (index < inputNames.size - 1) Divider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    // 智能排工页为首页
    var selectedTab by remember { mutableIntStateOf(3) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var isTableFullscreen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importFromExcel(it) } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { viewModel.exportToExcel(it) } }

    Scaffold(
        topBar = {
            if (!isTableFullscreen) {
            // 横屏时极限压缩顶部
            if (isLandscape) {
                TopAppBar(
                    title = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("智能排工", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            // 统计信息并入标题栏
                            val result by viewModel.dispatchResult.collectAsState()
                            result?.let { r ->
                                Text("总${r.totalPeople}", fontSize = 12.sp, color = Color(0xFF666666))
                                Spacer(Modifier.width(4.dp))
                                Text("假${r.leaveCount}", fontSize = 12.sp, color = Color(0xFFC62828))
                                Spacer(Modifier.width(4.dp))
                                Text("分${r.assignedCount}", fontSize = 12.sp, color = Color(0xFF1976D2))
                                Spacer(Modifier.width(4.dp))
                                Text(if (r.remainingCount >= 0) "余${r.remainingCount}" else "缺${-r.remainingCount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    actions = {
                        // 发布版显示排工按钮
                        if (!BuildConfig.DEBUG) {
                            IconButton(onClick = { viewModel.autoDispatch() }) { 
                                Icon(Icons.Default.PlayArrow, "排工", modifier = Modifier.size(20.dp), tint = Color(0xFF1976D2))
                            }
                        }
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置", modifier = Modifier.size(20.dp)) }
                    },
                    modifier = Modifier.height(32.dp)
                )
            } else {
                TopAppBar(
                    title = { Text("智能排工系统", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    actions = {
                        // 发布版显示排工按钮
                        if (!BuildConfig.DEBUG) {
                            IconButton(onClick = { viewModel.autoDispatch() }) { 
                                Icon(Icons.Default.PlayArrow, "排工", tint = Color(0xFF1976D2))
                            }
                        }
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入") }
                        IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
                    }
                )
            }
            }
        },
        bottomBar = {
            if (!isTableFullscreen) {
            val tabTitles = if (isLandscape) listOf("请假", "评分", "流程", "排工") else listOf("人员名单", "工序评分", "工序流程", "智能排工")
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(40.dp)
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = if (isLandscape) 11.sp else 13.sp) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        },
        floatingActionButton = {
            if (selectedTab == 3) {
                FloatingActionButton(
                    onClick = {
                        isTableFullscreen = !isTableFullscreen
                        val activity = context as? android.app.Activity
                        activity?.let {
                            if (isTableFullscreen) {
                                @Suppress("DEPRECATION")
                                it.window.decorView.systemUiVisibility = (
                                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    )
                                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                it.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 0.dp, top = 0.dp, end = 24.dp, bottom = 24.dp).size(36.dp),
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Fullscreen, "全屏", modifier = Modifier.size(16.dp))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> LeaveTab(viewModel)
                1 -> SkillScoreTab(viewModel)
                2 -> ProcessFlowTab(viewModel)
                3 -> DispatchTab(viewModel, isLandscape)
            }
        }

        // 设置页面（全屏对话框）
        if (showSettings) {
            SettingsScreen(viewModel = viewModel, onDismiss = { showSettings = false })
        }
    }
}

// ========== Tab 1: 请假人员（紧凑单行布局） ==========
@Composable
fun LeaveTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    var deletingPerson by remember { mutableStateOf<Person?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总人数", persons.size.toString())
                StatItem("请假", persons.count { it.onLeave }.toString(), Color(0xFFC62828))
                StatItem("可用", persons.count { !it.onLeave }.toString(), Color(0xFF2E7D32))
            }
            Divider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(persons, key = { it.id }) { person ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (person.onLeave) Icons.Default.PersonOff else Icons.Default.Person, null, tint = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        // 显示工号
                        if (person.employeeId.isNotBlank()) {
                            Text(person.employeeId, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333), modifier = Modifier.width(70.dp))
                        } else {
                            Spacer(Modifier.width(70.dp))
                        }
                        Text(person.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(if (person.onLeave) "请假中" else "在岗", fontSize = 12.sp, color = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { 
                            if (BuildConfig.DEBUG) {
                                viewModel.toggleLeaveAndDispatch(person)
                            } else {
                                viewModel.toggleLeave(person)
                            }
                        }, modifier = Modifier.size(32.dp)) { Icon(if (person.onLeave) Icons.Default.CheckCircle else Icons.Default.RemoveCircle, null, tint = if (person.onLeave) Color(0xFF2E7D32) else Color(0xFFC62828), modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { deletingPerson = person; showDeleteConfirm.value = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(20.dp)) }
                    }
                    Divider(modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        FloatingActionButton(
            onClick = { showAddDialog.value = true },
            modifier = Modifier.padding(start = 0.dp, top = 0.dp, end = 24.dp, bottom = 24.dp).align(Alignment.BottomEnd).size(40.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.Add, "添加人员", modifier = Modifier.size(20.dp)) }
    }
    if (showAddDialog.value) {
        var name by remember { mutableStateOf("") }
        var employeeId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog.value = false },
            title = { Text("添加人员") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = employeeId, onValueChange = { employeeId = it }, label = { Text("工号（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { viewModel.addPerson(name.trim(), employeeId.trim()); showAddDialog.value = false } }, enabled = name.isNotBlank()) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showAddDialog.value = false }) { Text("取消") } }
        )
    }
    if (showDeleteConfirm.value && deletingPerson != null) {
        AlertDialog(onDismissRequest = { showDeleteConfirm.value = false }, title = { Text("确认删除") }, text = { Text("确定要删除「${deletingPerson!!.name}」吗？") }, confirmButton = { TextButton(onClick = { viewModel.deletePerson(deletingPerson!!); showDeleteConfirm.value = false }) { Text("删除", color = Color(0xFFC62828)) } }, dismissButton = { TextButton(onClick = { showDeleteConfirm.value = false }) { Text("取消") } })
    }
}

// ========== Tab 2: 工序评分（固定左上角姓名单元格，行高28dp） ==========
@Composable
fun SkillScoreTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val processNames by viewModel.allProcessNames.collectAsState()
    val scoreVer by viewModel.scoreVersion.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    if (persons.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请先导入Excel或添加人员", color = MaterialTheme.colorScheme.outline) }
        return
    }

    var scoreMap by remember { mutableStateOf<Map<Pair<Int, String>, Int>>(emptyMap()) }
    LaunchedEffect(persons, scoreVer) {
        val map = mutableMapOf<Pair<Int, String>, Int>()
        for (person in persons) {
            val scores = repo.getScoresByPerson(person.id)
            for (s in scores) { map[Pair(person.id, s.processName)] = s.score }
        }
        scoreMap = map
    }

    val showEditDialog = remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }
    var editingProcess by remember { mutableStateOf("") }
    var currentScore by remember { mutableStateOf("0") }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定左上角"姓名"单元格 + 可滚动的工序表头
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.width(72.dp).height(28.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("姓名", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(MaterialTheme.colorScheme.primaryContainer)) {
                    processNames.forEach { process ->
                        Box(modifier = Modifier.width(64.dp).height(28.dp), contentAlignment = Alignment.Center) { Text(process, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            Divider()
            // 数据行：姓名列固定，评分列随水平滚动
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(persons, key = { it.id }) { person ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.width(72.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                            processNames.forEach { process ->
                                val score = scoreMap[Pair(person.id, process)] ?: 0
                                val bgColor = when { score >= 7 -> Color(0xFFE8F5E9); score >= 4 -> Color(0xFFFFFFF3); score > 0 -> Color(0xFFFFF3E0); else -> Color(0xFFFAFAFA) }
                                Box(modifier = Modifier.width(64.dp).height(28.dp).background(bgColor).border(0.5.dp, Color(0xFFE0E0E0)).clickable { editingPerson = person; editingProcess = process; currentScore = score.toString(); showEditDialog.value = true }, contentAlignment = Alignment.Center) {
                                    Text(if (score > 0) score.toString() else "", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (score >= 7) Color(0xFF2E7D32) else if (score > 0) Color(0xFFF57F17) else Color(0xFFBDBDBD))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog.value && editingPerson != null) {
        AlertDialog(onDismissRequest = { showEditDialog.value = false }, title = { Text("编辑评分") }, text = { Column { Text("${editingPerson!!.name} - $editingProcess"); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = currentScore, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) currentScore = it }, label = { Text("评分") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { viewModel.setSkillScore(editingPerson!!.id, editingProcess, currentScore.toIntOrNull() ?: 0); scoreMap = scoreMap.toMutableMap().apply { put(Pair(editingPerson!!.id, editingProcess), currentScore.toIntOrNull() ?: 0) }; showEditDialog.value = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showEditDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 3: 工序流程（固定左上角型号名称单元格，行高28dp） ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val showAddProductDialog = remember { mutableStateOf(false) }
    val showAddProcessDialog = remember { mutableStateOf(false) }
    val showDeleteProductConfirm = remember { mutableStateOf(false) }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }

    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    val maxProcesses = processMap.values.maxOfOrNull { it.size } ?: 0
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无产品数据", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Button(onClick = { showAddProductDialog.value = true }) { Text("添加产品") } }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 固定左上角"型号名称"单元格 + 可滚动的表头
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.width(120.dp).height(28.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("型号名称", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(MaterialTheme.colorScheme.primaryContainer)) {
                        Box(modifier = Modifier.width(60.dp).height(28.dp), contentAlignment = Alignment.Center) { Text("产能", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Box(modifier = Modifier.width(50.dp).height(28.dp), contentAlignment = Alignment.Center) { Text("人数", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        repeat(maxProcesses) { i ->
                            Box(modifier = Modifier.width(72.dp).height(28.dp), contentAlignment = Alignment.Center) { Text("工序${i + 1}", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        }
                        Box(modifier = Modifier.width(48.dp).height(28.dp)) {}
                    }
                }
                Divider()
                // 数据行：型号名称列固定，其余列随水平滚动
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(products, key = { it.id }) { product ->
                        val processes = processMap[product.id] ?: emptyList()
                        val rowBg = if (product.isFixed) Color(0xFFFFF9C4) else Color.Transparent
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.width(120.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)).padding(horizontal = 4.dp).background(rowBg), contentAlignment = Alignment.CenterStart) { Text(product.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState).background(rowBg)) {
                                Box(modifier = Modifier.width(60.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.capacity.toString(), fontSize = 13.sp) }
                                Box(modifier = Modifier.width(50.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.requiredPeople.toString(), fontSize = 13.sp) }
                                // 工序列表（固定产品黄色背景）
                                for (i in 0 until maxProcesses) {
                                    val pp = processes.getOrNull(i)
                                    Box(modifier = Modifier.width(72.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (pp != null) { Text(pp.processName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                Box(modifier = Modifier.width(48.dp).height(28.dp), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = { deletingProduct = product; showDeleteProductConfirm.value = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAddProductDialog.value = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(start = 0.dp, top = 0.dp, end = 24.dp, bottom = 24.dp).size(40.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.Add, "添加产品", modifier = Modifier.size(20.dp)) }
    }

    if (showAddProductDialog.value) {
        var name by remember { mutableStateOf("") }
        var capacity by remember { mutableStateOf("") }
        var people by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddProductDialog.value = false }, title = { Text("添加产品") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("产品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = capacity, onValueChange = { if (it.all { c -> c.isDigit() }) capacity = it }, label = { Text("产能") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = people, onValueChange = { if (it.all { c -> c.isDigit() }) people = it }, label = { Text("需求人数") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { viewModel.addProduct(name.trim(), capacity.toIntOrNull() ?: 0, people.toIntOrNull() ?: 0); showAddProductDialog.value = false } }, enabled = name.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddProductDialog.value = false }) { Text("取消") } })
    }
    if (showAddProcessDialog.value) {
        var processName by remember { mutableStateOf("") }
        var selectedProduct by remember { mutableStateOf(products.firstOrNull()?.name ?: "") }
        AlertDialog(onDismissRequest = { showAddProcessDialog.value = false }, title = { Text("添加工序") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = selectedProduct, onValueChange = { selectedProduct = it }, label = { Text("产品") }, singleLine = true, readOnly = true, modifier = Modifier.fillMaxWidth().clickable { }); OutlinedTextField(value = processName, onValueChange = { processName = it }, label = { Text("工序名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { val p = products.find { it.name == selectedProduct }; if (p != null && processName.isNotBlank()) { viewModel.addProcessToProduct(p.id, processName.trim()); showAddProcessDialog.value = false } }, enabled = processName.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddProcessDialog.value = false }) { Text("取消") } })
    }
    if (showDeleteProductConfirm.value && deletingProduct != null) {
        AlertDialog(onDismissRequest = { showDeleteProductConfirm.value = false }, title = { Text("确认删除") }, text = { Text("确定要删除「${deletingProduct!!.name}」及其所有工序吗？") }, confirmButton = { TextButton(onClick = { viewModel.deleteProduct(deletingProduct!!); showDeleteProductConfirm.value = false }) { Text("删除", color = Color(0xFFC62828)) } }, dismissButton = { TextButton(onClick = { showDeleteProductConfirm.value = false }) { Text("取消") } })
    }
}

// ========== Tab 4: 智能排工（自动排工，横屏优化，缩放功能） ==========
@Composable
fun DispatchTab(viewModel: MainViewModel, isLandscape: Boolean = false) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val persons by viewModel.allPersons.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val inputNames by viewModel.inputNames.collectAsState()
    val focusedIndex by viewModel.focusedInputIndex.collectAsState()
    val matchedProducts by viewModel.matchedProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    var showDebugLogs by remember { mutableStateOf(true) }
    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    val unassignedScrollState = rememberScrollState()
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    val selectedProducts = inputNames.mapNotNull { name ->
        if (name.isBlank()) null
        else products.find { it.name.equals(name.trim(), ignoreCase = true) }
    }

    // 按输入框索引匹配分配结果（支持相同型号多实例）
    val assignmentsByIndex = remember(result, inputNames) {
        val r = result ?: return@remember emptyMap<Int, List<ProcessAssignment>>()
        val map = mutableMapOf<Int, List<ProcessAssignment>>()
        val nameCount = mutableMapOf<String, Int>()
        for ((index, name) in inputNames.withIndex()) {
            if (name.isBlank()) continue
            val cleanName = products.find { it.name.equals(name.trim(), ignoreCase = true) }?.name
                ?: continue
            val count = nameCount.getOrDefault(cleanName, 0)
            nameCount[cleanName] = count + 1
            val uniqueKey = "${cleanName}@$count"
            // 按 rowIndex 匹配分配人员
            map[index] = r.assignments.filter { it.productName == uniqueKey }
        }
        map
    }
    val scrollState = rememberScrollState()
    val leavePeople = persons.filter { it.onLeave }

    // 自动排工（仅调试版）
    if (BuildConfig.DEBUG) {
        LaunchedEffect(inputNames) {
            kotlinx.coroutines.delay(500)
            if (inputNames.all { it.isBlank() }) {
                viewModel.autoDispatch()
            }
        }
    }

    // 从设置读取尺寸
    val settingsFontSize by viewModel.fontSize.collectAsState()
    val settingsRowHeight by viewModel.rowHeight.collectAsState()
    val settingsColWidth by viewModel.colWidth.collectAsState()
    val rowHeight = settingsRowHeight.dp
    val colWidth = settingsColWidth.dp
    val productWidth = colWidth * 2
    val fontSize = settingsFontSize.sp

    Column(modifier = Modifier.fillMaxSize()) {
        // 竖屏时显示统计栏（横屏时在标题栏显示）
        if (!isLandscape) {
            result?.let { r ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("总人数", r.totalPeople.toString())
                    StatItem("请假", r.leaveCount.toString(), Color(0xFFC62828))
                    StatItem("已分配", r.assignedCount.toString(), Color(0xFF1976D2))
                    StatItem(if (r.remainingCount >= 0) "剩余" else "欠缺", kotlin.math.abs(r.remainingCount).toString(), if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                }
            }
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 调试日志区域（仅调试版显示）
        if (BuildConfig.DEBUG) {
            val debugLogs = result?.debugLogs ?: emptyList()
            if (debugLogs.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().height(if (isLandscape) 60.dp else 80.dp).background(Color(0xFFFFF8E1)).padding(2.dp).clickable { showDebugLogs = !showDebugLogs }) {
                    Text("📋 调试日志（共${debugLogs.size}条）点击${if (showDebugLogs) "收起" else "展开"}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    if (showDebugLogs) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(debugLogs.size) { index ->
                                val log = debugLogs[index]
                                val logColor = when {
                                    log.startsWith("[固定列") -> Color(0xFFE65100)
                                    log.startsWith("→") -> Color(0xFF1565C0)
                                    log.contains("缓存") || log.contains("productMap") -> Color(0xFFC62828)
                                    else -> Color(0xFF333333)
                                }
                                Text(log, fontSize = 9.sp, color = logColor, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
                            }
                        }
                    } else {
                        LazyRow(modifier = Modifier.fillMaxSize()) {
                            items(debugLogs.size) { index ->
                                Text(debugLogs[index], fontSize = 8.sp, color = Color(0xFF666666), modifier = Modifier.padding(horizontal = 4.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // 表格区域
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 第一行：请假人员标题 + 输入框（两行显示）
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).background(Color(0xFF90CAF9))) {
                    Box(modifier = Modifier.width(60.dp).height(rowHeight * 2).background(Color.White, RoundedCornerShape(2.dp)).border(0.5.dp, Color(0xFFE0E0E0)).padding(1.dp), contentAlignment = Alignment.TopCenter) {
                        Text("请假\n人员", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    inputNames.forEachIndexed { index, name ->
                        Box(modifier = Modifier.width(productWidth).height(rowHeight * 2).padding(1.dp)) {
                            BasicTextField(
                                value = name,
                                onValueChange = { viewModel.updateInputName(index, it) },
                                modifier = Modifier.fillMaxSize().onFocusChanged { focusState ->
                                    if (focusState.isFocused) viewModel.setFocusedInput(index)
                                    else if (focusedIndex == index) viewModel.clearFocus()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                singleLine = false,
                                maxLines = 2,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp, vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
                                        if (name.isEmpty()) {
                                            Text("型号${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAAAAAA), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }
                // 自动完成下拉列表（垂直展开）
                if (focusedIndex >= 0 && matchedProducts.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFF5F5F5)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(matchedProducts) { productName ->
                            Text(
                                text = productName,
                                fontSize = fontSize,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .clickable { 
                                        if (BuildConfig.DEBUG) {
                                            viewModel.selectProductAndDispatch(focusedIndex, productName)
                                        } else {
                                            viewModel.selectProduct(focusedIndex, productName)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color(0xFF1565C0),
                                maxLines = 1
                            )
                        }
                    }
                }
                Divider()
                // 第二行：请假人名 + 产能/人数
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                    Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFCDD2)), contentAlignment = Alignment.Center) {
                        val p = leavePeople.getOrNull(0)
                        if (p != null) Text(p.name, fontSize = fontSize, fontWeight = FontWeight.Medium, color = Color(0xFFC62828)) else Text("")
                    }
                    inputNames.forEachIndexed { index, name ->
                        val product = if (name.isNotBlank()) products.find { it.name.equals(name.trim(), ignoreCase = true) } else null
                        // 固定产品显示黄色背景
                        val cellBg = if (product?.isFixed == true) Color(0xFFFFF9C4) else Color.Transparent
                        Row(modifier = Modifier.width(productWidth).height(rowHeight).background(cellBg)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                Text(product?.capacity?.toString() ?: "", fontSize = fontSize, color = Color(0xFF666666))
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                Text(product?.requiredPeople?.toString() ?: "", fontSize = fontSize, color = Color(0xFF666666))
                            }
                        }
                    }
                }
                Divider()
                // 数据行
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val maxProductRows = inputNames.indices.maxOfOrNull { index ->
                        val name = inputNames[index]
                        val product = if (name.isNotBlank()) products.find { it.name.equals(name.trim(), ignoreCase = true) } else null
                        val processes = if (product != null) (processMap[product.id] ?: emptyList()) else emptyList()
                        val assignments = assignmentsByIndex[index] ?: emptyList()
                        maxOf(processes.size, assignments.size)
                    } ?: 0
                    val maxRows = maxOf(maxProductRows, max(leavePeople.size - 1, 0), 1)

                    items(maxRows) { rowIndex ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                            Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFCDD2)), contentAlignment = Alignment.Center) {
                                val person = leavePeople.getOrNull(rowIndex + 1)
                                if (person != null) Text(person.name, fontSize = fontSize, fontWeight = FontWeight.Medium, color = Color(0xFFC62828))
                            }
                            inputNames.forEachIndexed { index, name ->
                                val product = if (name.isNotBlank()) products.find { it.name.equals(name.trim(), ignoreCase = true) } else null
                                val processes = if (product != null) (processMap[product.id] ?: emptyList()) else emptyList()
                                val assignments = assignmentsByIndex[index] ?: emptyList()
                                val processName = processes.getOrNull(rowIndex)?.processName ?: ""
                                // 按 rowIndex 匹配分配人员（rowIndex = 3 + 工序偏移）
                                val currentRowIndex = rowIndex + 3
                                val assignedPerson = assignments.find { it.rowIndex == currentRowIndex }?.assignedPerson ?: ""
                                // 固定产品显示深黄色背景
                                val cellBg = if (product?.isFixed == true) Color(0xFFFFD54F) else Color.Transparent

                                Row(modifier = Modifier.width(productWidth).height(rowHeight).background(cellBg)) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (processName.isNotEmpty()) Text(processName, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF424242))
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFF1976D2)), contentAlignment = Alignment.Center) {
                                        if (assignedPerson.isNotEmpty()) Text(assignedPerson, fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
                // 未分配人员（在LazyColumn外部）
                val unassigned = result?.unassignedPeople ?: emptyList()
                if (unassigned.isNotEmpty()) {
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(unassignedScrollState)) {
                            Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)).background(Color(0xFFFFE0B2)), contentAlignment = Alignment.Center) {
                                Text("未分配", fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                            }
                            unassigned.forEach { person ->
                                Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                    Text(person, fontSize = fontSize, color = Color(0xFF757575), fontStyle = FontStyle.Italic)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
