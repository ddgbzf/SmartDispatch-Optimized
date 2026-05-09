package com.example.smartdispatch

import android.app.Application
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.smartdispatch.ui.theme.智能排工Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DispatchApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { DispatchRepository(
        database.personDao(), database.skillScoreDao(),
        database.productDao(), database.productProcessDao(), database.assignmentDao()
    )}
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as DispatchApplication).repository
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

    // 评分数据缓存（用于触发UI刷新）
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }
    fun addPerson(name: String) = viewModelScope.launch { repo.addPerson(name) }
    fun toggleLeave(person: Person) = viewModelScope.launch { repo.updatePerson(person.copy(onLeave = !person.onLeave)) }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }

    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
        _scoreVersion.update { it + 1 } // 触发UI刷新
    }

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { repo.deleteProduct(product) }

    fun addProcessToProduct(productId: Int, processName: String) = viewModelScope.launch {
        val processes = repo.getProcessesOnce(productId)
        repo.addProcess(productId, processName, processes.size)
    }
    fun deleteProcessFromProduct(process: ProductProcess) = viewModelScope.launch { repo.deleteProcess(process) }

    fun executeDispatch() = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val engine = DispatchEngine()
            val persons = allPersons.first()
            val products = allProducts.first()
            val processNames = allProcessNames.first()
            val peopleNames = persons.map { it.name }
            val leaveNames = persons.filter { it.onLeave }.map { it.name }
            val productMap = mutableMapOf<String, com.example.smartdispatch.model.Product>()
            for (product in products) {
                val processes = repo.getProcessesOnce(product.id)
                productMap[product.name] = com.example.smartdispatch.model.Product(
                    product.name, product.capacity, product.requiredPeople,
                    processes.map { it.processName }
                )
            }
            val scoreMap = mutableMapOf<String, MutableMap<String, Int>>()
            for (person in persons) {
                val scores = repo.getScoresByPerson(person.id)
                val pScores = mutableMapOf<String, Int>()
                for (s in scores) { pScores[s.processName] = s.score }
                scoreMap[person.name] = pScores
            }
            engine.setSkillScoresData(scoreMap)
            val result = withContext(Dispatchers.IO) {
                engine.runWithData(peopleNames, leaveNames, productMap, processNames)
            }
            _dispatchResult.value = result
            addLog("✅ 排工完成！${result.statusMessage}")
        } catch (e: Exception) {
            addLog("❌ 错误: ${e.message}")
        }
        _isLoading.value = false
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
                        for (person in allP) {
                            if (person.name in data.leaveList) { r.updatePerson(person.copy(onLeave = true)) }
                        }
                        val updatedP = r.allPersons.first()
                        for (person in updatedP) {
                            val scores = data.skillScores[person.name] ?: continue
                            for ((processName, score) in scores) { r.setSkillScore(person.id, processName, score) }
                        }
                        for ((name, product) in data.products) {
                            val pid = r.addProduct(name, product.capacity, product.requiredPeople)
                            for ((offset, processName) in product.processes.withIndex()) {
                                r.addProcess(pid.toInt(), processName, offset)
                            }
                        }
                    }
                    addLog("✅ 导入成功: ${data.people.size}人, ${data.products.size}个产品")
                    Toast.makeText(ctx, "导入成功: ${data.people.size}人, ${data.products.size}个产品", Toast.LENGTH_SHORT).show()
                } else {
                    addLog("❌ $error")
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show()
                }
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importFromExcel(it) } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { Toast.makeText(context, "导出功能开发中...", Toast.LENGTH_SHORT).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能排工系统", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileUpload, "导入") }
                    IconButton(onClick = { exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx") }) { Icon(Icons.Default.FileDownload, "导出") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
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
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> LeaveTab(viewModel)
                1 -> SkillScoreTab(viewModel)
                2 -> ProcessFlowTab(viewModel)
                3 -> DispatchTab(viewModel)
            }
        }
    }
}

// ========== Tab 1: 请假人员 ==========
@Composable
fun LeaveTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总人数", persons.size.toString())
                StatItem("请假", persons.count { it.onLeave }.toString(), Color(0xFFC62828))
                StatItem("可用", persons.count { !it.onLeave }.toString(), Color(0xFF2E7D32))
            }
            Divider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(persons, key = { it.id }) { person ->
                    ListItem(
                        headlineContent = { Text(person.name) },
                        supportingContent = { Text(if (person.onLeave) "请假中" else "在岗") },
                        leadingContent = { Icon(if (person.onLeave) Icons.Default.PersonOff else Icons.Default.Person, null, tint = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.toggleLeave(person) }) { Icon(if (person.onLeave) Icons.Default.CheckCircle else Icons.Default.RemoveCircle, null, tint = if (person.onLeave) Color(0xFF2E7D32) else Color(0xFFC62828)) }
                                IconButton(onClick = { viewModel.deletePerson(person) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828)) }
                            }
                        }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        FloatingActionButton(onClick = { showAddDialog.value = true }, modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "添加人员") }
    }
    if (showAddDialog.value) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddDialog.value = false }, title = { Text("添加人员") }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { viewModel.addPerson(name.trim()); showAddDialog.value = false } }, enabled = name.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 2: 工序评分（整体联动滚动 + 评分保存后刷新） ==========
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

    // 加载评分（scoreVer变化时重新加载）
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text("人员技能评分（点击单元格编辑）", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        // 整体联动滚动：垂直LazyColumn，水平LazyRow嵌套在item中
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 表头行
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.width(72.dp).height(40.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("姓名", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    LazyRow(modifier = Modifier.fillMaxWidth()) {
                        processNames.forEach { process ->
                            item(key = process) {
                                Box(modifier = Modifier.width(64.dp).height(40.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(process, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            // 每行一个人
            items(persons, key = { it.id }) { person ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 固定姓名
                    Box(modifier = Modifier.width(72.dp).height(40.dp).border(0.5.dp, Color(0xFFE0E0E0)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    // 可水平滚动的评分列
                    LazyRow(modifier = Modifier.fillMaxWidth()) {
                        processNames.forEach { process ->
                            item(key = "${person.id}_$process") {
                                val score = scoreMap[Pair(person.id, process)] ?: 0
                                val bgColor = when { score >= 7 -> Color(0xFFE8F5E9); score >= 4 -> Color(0xFFFFFFF3); score > 0 -> Color(0xFFFFF3E0); else -> Color(0xFFFAFAFA) }
                                Box(modifier = Modifier.width(64.dp).height(40.dp).background(bgColor).border(0.5.dp, Color(0xFFE0E0E0)).clickable { editingPerson = person; editingProcess = process; currentScore = score.toString(); showEditDialog.value = true }, contentAlignment = Alignment.Center) {
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
        AlertDialog(
            onDismissRequest = { showEditDialog.value = false },
            title = { Text("编辑评分") },
            text = {
                Column {
                    Text("${editingPerson!!.name} - $editingProcess")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = currentScore, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) currentScore = it }, label = { Text("评分") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSkillScore(editingPerson!!.id, editingProcess, currentScore.toIntOrNull() ?: 0)
                    // 立即更新本地缓存以快速反映
                    val newScore = currentScore.toIntOrNull() ?: 0
                    scoreMap = scoreMap.toMutableMap().apply { put(Pair(editingPerson!!.id, editingProcess), newScore) }
                    showEditDialog.value = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog.value = false }) { Text("取消") } }
        )
    }
}

// ========== Tab 3: 工序流程（可编辑，无箭头） ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val showAddProductDialog = remember { mutableStateOf(false) }
    val showAddProcessDialog = remember { mutableStateOf(false) }
    var selectedProductId by remember { mutableStateOf(0) }

    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    LaunchedEffect(products) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无产品数据", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Button(onClick = { showAddProductDialog.value = true }) { Text("添加产品") } }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products, key = { it.id }) { product ->
                    val processes = processMap[product.id] ?: emptyList()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // 产品标题行
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(product.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("产能: ${product.capacity}  需求: ${product.requiredPeople}人", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(onClick = { viewModel.deleteProduct(product) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(32.dp)) }
                            }
                            // 工序列表
                            if (processes.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                processes.forEachIndexed { index, pp ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}. ${pp.processName}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.deleteProcessFromProduct(pp) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(16.dp)) }
                                    }
                                }
                            }
                            // 添加工序按钮
                            TextButton(onClick = { selectedProductId = product.id; showAddProcessDialog.value = true }) { Text("+ 添加工序") }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showAddProductDialog.value = true }, modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "添加产品") }
    }

    // 添加产品对话框
    if (showAddProductDialog.value) {
        var name by remember { mutableStateOf("") }
        var capacity by remember { mutableStateOf("") }
        var people by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddProductDialog.value = false }, title = { Text("添加产品") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("产品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = capacity, onValueChange = { if (it.all { c -> c.isDigit() }) capacity = it }, label = { Text("产能") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = people, onValueChange = { if (it.all { c -> c.isDigit() }) people = it }, label = { Text("需求人数") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { viewModel.addProduct(name.trim(), capacity.toIntOrNull() ?: 0, people.toIntOrNull() ?: 0); showAddProductDialog.value = false } }, enabled = name.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddProductDialog.value = false }) { Text("取消") } })
    }

    // 添加工序对话框
    if (showAddProcessDialog.value) {
        var processName by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddProcessDialog.value = false }, title = { Text("添加工序") }, text = { OutlinedTextField(value = processName, onValueChange = { processName = it }, label = { Text("工序名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { if (processName.isNotBlank()) { viewModel.addProcessToProduct(selectedProductId, processName.trim()); showAddProcessDialog.value = false } }, enabled = processName.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddProcessDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 4: 智能排工（参考原表布局） ==========
@Composable
fun DispatchTab(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val products by viewModel.allProducts.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 执行按钮
        Button(onClick = { viewModel.executeDispatch() }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
            if (isLoading) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) } else { Icon(Icons.Default.PlayArrow, null) }
            Spacer(Modifier.width(8.dp)); Text("执行排工")
        }

        // 统计卡片
        result?.let { r ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (r.remainingCount >= 0) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("总人数", r.totalPeople.toString())
                    StatItem("请假", r.leaveCount.toString())
                    StatItem("已分配", r.assignedCount.toString())
                    StatItem(if (r.remainingCount >= 0) "剩余" else "欠缺", kotlin.math.abs(r.remainingCount).toString(), if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                }
            }
        }

        // 排工结果 - 按产品分组显示（参考原表布局）
        if (result != null && result!!.assignments.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxSize().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("排工结果", fontWeight = FontWeight.Bold)
                        Text("${result!!.assignments.size}条分配", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Divider()
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        // 按产品分组
                        val grouped = result!!.assignments.groupBy { it.productName }
                        grouped.forEach { (productName, assignments) ->
                            // 产品标题
                            item {
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text(" $productName ", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(8.dp))
                                }
                            }
                            // 该产品下的工序分配
                            items(assignments) { assignment ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(assignment.processName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(assignment.assignedPerson ?: "未分配", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (assignment.assignedPerson != null) Color(0xFF1976D2) else Color(0xFFC62828))
                                }
                            }
                            // 分隔线
                            item { Divider(modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                        // 未分配人员
                        if (result!!.unassignedPeople.isNotEmpty()) {
                            item {
                                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color(0xFFFFF3E0), shape = RoundedCornerShape(4.dp)) {
                                    Text(" 未分配人员(${result!!.unassignedPeople.size}人) ", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFE65100), modifier = Modifier.padding(8.dp))
                                }
                            }
                            items(result!!.unassignedPeople) { person ->
                                Text(person, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else {
            // 日志
            Card(modifier = Modifier.fillMaxSize().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("运行日志", fontWeight = FontWeight.Bold); TextButton(onClick = { viewModel.clearLogs() }) { Text("清空") } }
                    Divider()
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(logs) { log -> Text(log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
