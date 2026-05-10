package com.example.smartdispatch

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartdispatch.data.AppDatabase
import com.example.smartdispatch.data.DispatchRepository
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
        database.productDao(), database.productProcessDao(), database.assignmentDao()
    )}
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as DispatchApplication).repository
    private val prefs = application.getSharedPreferences("dispatch_state", Context.MODE_PRIVATE)
    
    val allPersons = repo.allPersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val leavePersons = repo.leavePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val availablePersons = repo.availablePersons.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProcessNames = repo.allProcessNames.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allProducts = repo.allProducts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allAssignments = repo.allAssignments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _logs = MutableStateFlow(listOf<String>())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _dispatchResult = MutableStateFlow<DispatchResult?>(null)
    val dispatchResult: StateFlow<DispatchResult?> = _dispatchResult.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()
    
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
        val newList = _inputNames.value.toMutableList().apply { set(index, value) }
        saveInputNames(newList)
        _inputNames.value = newList
    }
    
    // 当前正在编辑的输入框索引
    private val _focusedInputIndex = MutableStateFlow(-1)
    val focusedInputIndex: StateFlow<Int> = _focusedInputIndex.asStateFlow()
    fun setFocusedInput(index: Int) { _focusedInputIndex.value = index }
    fun clearFocus() { _focusedInputIndex.value = -1 }
    
    // 匹配的型号名称列表（用于自动完成，输入2字符以上才显示）
    val matchedProducts: StateFlow<List<String>> = combine(_inputNames, _focusedInputIndex, allProducts) { names, focusIndex, products ->
        if (focusIndex < 0 || focusIndex >= names.size) emptyList()
        else {
            val text = names[focusIndex].trim()
            if (text.length < 2) emptyList()
            else products.filter { it.name.contains(text, ignoreCase = true) }.map { it.name }.take(10)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    fun selectProduct(index: Int, productName: String) {
        updateInputName(index, productName)
        _focusedInputIndex.value = -1 // 关闭下拉列表
    }

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }
    fun addPerson(name: String, employeeId: String = "") = viewModelScope.launch { repo.addPerson(name, employeeId) }
    fun toggleLeave(person: Person) = viewModelScope.launch { 
        repo.updatePerson(person.copy(onLeave = !person.onLeave))
        // 请假人员变化时自动执行排工
        autoDispatch()
    }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }
    
    // 自动执行排工（当输入框变化时调用）
    fun autoDispatch() {
        viewModelScope.launch {
            // 保留重复的型号名称，支持同一型号多实例（如两条产线）
            val names = _inputNames.value.mapNotNull { name ->
                if (name.isNotBlank()) {
                    allProducts.first().find { it.name.contains(name.trim(), ignoreCase = true) }?.name
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

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { repo.deleteProduct(product) }
    fun toggleProductFixed(product: Product) = viewModelScope.launch { 
        repo.updateProduct(product.copy(isFixed = !product.isFixed))
    }

    fun addProcessToProduct(productId: Int, processName: String) = viewModelScope.launch {
        val processes = repo.getProcessesOnce(productId)
        repo.addProcess(productId, processName, processes.size)
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
            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames)
            }
            _dispatchResult.value = result
            addLog("✅ 排工完成！分配${result.assignedCount}人, ${result.statusMessage}")
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
                        for ((index, name) in data.people.withIndex()) { r.addPerson(name) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    // 智能排工页为首页
    var selectedTab by remember { mutableIntStateOf(3) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importFromExcel(it) } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { Toast.makeText(context, "导出功能开发中...", Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
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
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出", modifier = Modifier.size(20.dp)) }
                    },
                    modifier = Modifier.height(32.dp)
                )
            } else {
                TopAppBar(
                    title = { Text("智能排工系统", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    actions = {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入") }
                        IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出") }
                    }
                )
            }
        },
        bottomBar = {
            // 横屏时压缩底部导航
            if (isLandscape) {
                NavigationBar(modifier = Modifier.height(36.dp)) {
                    listOf("请假", "评分", "流程", "排工").forEachIndexed { index, title ->
                        NavigationBarItem(
                            icon = { when (index) { 0 -> Icon(Icons.Default.PersonOff, null, modifier = Modifier.size(18.dp)); 1 -> Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp)); 2 -> Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(18.dp)); else -> Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp)) } },
                            label = { Text(title, fontSize = 9.sp) },
                            selected = selectedTab == index,
                            onClick = { selectedTab = index }
                        )
                    }
                }
            } else {
                NavigationBar(modifier = Modifier.height(48.dp)) {
                    listOf("请假人员", "工序评分", "工序流程", "智能排工").forEachIndexed { index, title ->
                        NavigationBarItem(
                            icon = { when (index) { 0 -> Icon(Icons.Default.PersonOff, null); 1 -> Icon(Icons.Default.Star, null); 2 -> Icon(Icons.Default.AccountTree, null); else -> Icon(Icons.Default.PlayArrow, null) } },
                            label = { Text(title, fontSize = 11.sp) },
                            selected = selectedTab == index,
                            onClick = { selectedTab = index }
                        )
                    }
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
                        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (person.onLeave) Icons.Default.PersonOff else Icons.Default.Person, null, tint = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(person.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        // 显示工号
                        if (person.employeeId.isNotBlank()) {
                            Text(person.employeeId, fontSize = 11.sp, color = Color(0xFF757575), modifier = Modifier.width(60.dp))
                        } else {
                            Spacer(Modifier.width(60.dp))
                        }
                        Text(if (person.onLeave) "请假中" else "在岗", fontSize = 12.sp, color = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.toggleLeave(person) }, modifier = Modifier.size(32.dp)) { Icon(if (person.onLeave) Icons.Default.CheckCircle else Icons.Default.RemoveCircle, null, tint = if (person.onLeave) Color(0xFF2E7D32) else Color(0xFFC62828), modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { deletingPerson = person; showDeleteConfirm.value = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(20.dp)) }
                    }
                    Divider(modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        FloatingActionButton(onClick = { showAddDialog.value = true }, modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "添加人员") }
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
    LaunchedEffect(products) {
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
                        Box(modifier = Modifier.width(40.dp).height(28.dp), contentAlignment = Alignment.Center) { Text("固定", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
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
                                // 固定状态开关
                                Box(modifier = Modifier.width(40.dp).height(28.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = { viewModel.toggleProductFixed(product) }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            if (product.isFixed) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = "固定",
                                            tint = if (product.isFixed) Color(0xFFFBC02D) else Color(0xFFBDBDBD),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
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
        Column(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FloatingActionButton(onClick = { showAddProcessDialog.value = true }, containerColor = MaterialTheme.colorScheme.secondary) { Icon(Icons.Default.Add, "添加工序") }
            Spacer(Modifier.height(0.dp))
            FloatingActionButton(onClick = { showAddProductDialog.value = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "添加产品") }
        }
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

    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    LaunchedEffect(products) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    val selectedProducts = inputNames.mapNotNull { name ->
        if (name.isBlank()) null
        else products.find { it.name.contains(name.trim(), ignoreCase = true) }
    }

    // 按输入框索引匹配分配结果（支持相同型号多实例）
    val assignmentsByIndex = remember(result, inputNames) {
        val r = result ?: return@remember emptyMap<Int, List<ProcessAssignment>>()
        val map = mutableMapOf<Int, List<ProcessAssignment>>()
        val nameCount = mutableMapOf<String, Int>()
        for ((index, name) in inputNames.withIndex()) {
            if (name.isBlank()) continue
            val cleanName = products.find { it.name.contains(name.trim(), ignoreCase = true) }?.name ?: continue
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

    // 输入变化时自动执行排工
    LaunchedEffect(inputNames) {
        viewModel.autoDispatch()
    }

    // 根据横竖屏调整尺寸
    val rowHeight = if (isLandscape) 20.dp else 22.dp
    val colWidth = if (isLandscape) 45.dp else 50.dp
    val productWidth = colWidth * 2
    val fontSize = if (isLandscape) 11.sp else 12.sp

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

        // 调试日志区域（表格上方，不会被输入法遮挡）
        val debugLogs = result?.debugLogs ?: emptyList()
        if (debugLogs.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().height(if (isLandscape) 40.dp else 60.dp).background(Color(0xFFF5F5F5)).padding(2.dp)) {
                Text("📋 调试日志（共${debugLogs.size}条）", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                LazyRow(modifier = Modifier.fillMaxSize()) {
                    items(debugLogs.size) { index ->
                        val log = debugLogs[index]
                        val logColor = if (log.startsWith("→")) Color(0xFF1565C0) else Color(0xFF666666)
                        Text(log, fontSize = 8.sp, color = logColor, modifier = Modifier.padding(horizontal = 4.dp), maxLines = 1)
                    }
                }
            }
        }

        // 表格区域
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 第一行：请假人员标题 + 输入框
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).background(Color(0xFFBBDEFB))) {
                    Box(modifier = Modifier.width(60.dp).height(rowHeight), contentAlignment = Alignment.Center) { Text("请假", fontWeight = FontWeight.Bold, fontSize = fontSize) }
                    inputNames.forEachIndexed { index, name ->
                        Box(modifier = Modifier.width(productWidth).height(rowHeight).padding(1.dp)) {
                            BasicTextField(
                                value = name,
                                onValueChange = { viewModel.updateInputName(index, it) },
                                modifier = Modifier.fillMaxSize().onFocusChanged { focusState ->
                                    if (focusState.isFocused) viewModel.setFocusedInput(index)
                                    else if (focusedIndex == index) viewModel.clearFocus()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize, color = Color.Black),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                                decorationBox = { innerTextField ->
                                    Box(modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                                        if (name.isEmpty()) {
                                            Text("型号${index + 1}", fontSize = fontSize, color = Color(0xFFAAAAAA), maxLines = 1)
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
                                    .clickable { viewModel.selectProduct(focusedIndex, productName) }
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
                    Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                        val p = leavePeople.getOrNull(0)
                        if (p != null) Text(p.name, fontSize = fontSize) else Text("")
                    }
                    inputNames.forEachIndexed { index, name ->
                        val product = if (name.isNotBlank()) products.find { it.name.contains(name.trim(), ignoreCase = true) } else null
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val maxProductRows = inputNames.indices.maxOfOrNull { index ->
                        val name = inputNames[index]
                        val product = if (name.isNotBlank()) products.find { it.name.contains(name.trim(), ignoreCase = true) } else null
                        val processes = if (product != null) (processMap[product.id] ?: emptyList()) else emptyList()
                        val assignments = assignmentsByIndex[index] ?: emptyList()
                        maxOf(processes.size, assignments.size)
                    } ?: 0
                    val maxRows = maxOf(maxProductRows, max(leavePeople.size - 1, 0), 1)

                    items(maxRows) { rowIndex ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                            Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                val person = leavePeople.getOrNull(rowIndex + 1)
                                if (person != null) Text(person.name, fontSize = fontSize)
                            }
                            inputNames.forEachIndexed { index, name ->
                                val product = if (name.isNotBlank()) products.find { it.name.contains(name.trim(), ignoreCase = true) } else null
                                val processes = if (product != null) (processMap[product.id] ?: emptyList()) else emptyList()
                                val assignments = assignmentsByIndex[index] ?: emptyList()
                                val processName = processes.getOrNull(rowIndex)?.processName ?: ""
                                // 按 rowIndex 匹配分配人员（rowIndex = 3 + 工序偏移）
                                val currentRowIndex = rowIndex + 3
                                val assignedPerson = assignments.find { it.rowIndex == currentRowIndex }?.assignedPerson ?: ""
                                // 固定产品显示黄色背景
                                val cellBg = if (product?.isFixed == true) Color(0xFFFFF9C4) else Color.Transparent

                                Row(modifier = Modifier.width(productWidth).height(rowHeight).background(cellBg)) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (processName.isNotEmpty()) Text(processName, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF666666))
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        if (assignedPerson.isNotEmpty()) Text(assignedPerson, fontSize = fontSize, fontWeight = FontWeight.Medium, color = Color(0xFF1976D2))
                                    }
                                }
                            }
                        }
                    }

                    // 未分配人员紧跟数据行之后
                    val unassigned = result?.unassignedPeople ?: emptyList()
                    if (unassigned.isNotEmpty()) {
                        item {
                            Divider()
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                                Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                    Text("未分", fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                                }
                                unassigned.forEach { person ->
                                    Box(modifier = Modifier.width(60.dp).height(rowHeight).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        Text(person, fontSize = fontSize, color = Color(0xFFE65100))
                                    }
                                }
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
