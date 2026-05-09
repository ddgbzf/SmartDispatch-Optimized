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
        database.personDao(),
        database.skillScoreDao(),
        database.productDao(),
        database.productProcessDao(),
        database.assignmentDao()
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

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }

    fun addPerson(name: String) = viewModelScope.launch { repo.addPerson(name) }
    fun toggleLeave(person: Person) = viewModelScope.launch { repo.updatePerson(person.copy(onLeave = !person.onLeave)) }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }

    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
    }

    fun addProduct(name: String, capacity: Int, requiredPeople: Int) = viewModelScope.launch {
        repo.addProduct(name, capacity, requiredPeople)
    }
    fun updateProduct(product: Product) = viewModelScope.launch { repo.updateProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { repo.deleteProduct(product) }

    fun getScoresByPerson(personId: Int) = viewModelScope.launch { repo.getScoresByPerson(personId) }
    fun getProcessesOnce(productId: Int) = viewModelScope.launch { repo.getProcessesOnce(productId) }

    fun executeDispatch() = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val engine = DispatchEngine()
            val ctx = getApplication<Application>()
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

            // 从数据库加载评分数据
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
                        for ((index, name) in data.people.withIndex()) {
                            r.addPerson(name)
                        }
                        val allP = r.allPersons.first()
                        for (person in allP) {
                            if (person.name in data.leaveList) {
                                r.updatePerson(person.copy(onLeave = true))
                            }
                        }
                        val updatedP = r.allPersons.first()
                        for (person in updatedP) {
                            val scores = data.skillScores[person.name] ?: continue
                            for ((processName, score) in scores) {
                                r.setSkillScore(person.id, processName, score)
                            }
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
        } catch (e: Exception) {
            addLog("❌ ${e.message}")
        }
        _isLoading.value = false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            智能排工Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFromExcel(it) }
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> uri?.let { Toast.makeText(context, "导出功能开发中...", Toast.LENGTH_SHORT).show() } }

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

// ========== Tab 1: 请假人员（按原始顺序） ==========
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

// ========== Tab 2: 工序评分（上下+左右滚动，显示真实评分） ==========
@Composable
fun SkillScoreTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val processNames by viewModel.allProcessNames.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    if (persons.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请先导入Excel或添加人员", color = MaterialTheme.colorScheme.outline) }
        return
    }

    // 加载所有评分数据
    var scoreMap by remember { mutableStateOf<Map<Pair<Int, String>, Int>>(emptyMap()) }
    LaunchedEffect(persons) {
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

        // 固定表头 + 可滚动内容
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 固定姓名列
                Column(modifier = Modifier.width(72.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                    Box(modifier = Modifier.height(40.dp).width(72.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("姓名", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(persons) { person ->
                            Box(modifier = Modifier.height(40.dp).fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                // 可水平滚动的工序列
                LazyRow(modifier = Modifier.fillMaxSize()) {
                    processNames.forEach { process ->
                        item(key = process) {
                            Column(modifier = Modifier.width(64.dp).fillMaxHeight()) {
                                Box(modifier = Modifier.height(40.dp).width(64.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(process, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    items(persons) { person ->
                                        val score = scoreMap[Pair(person.id, process)] ?: 0
                                        val bgColor = when { score >= 7 -> Color(0xFFE8F5E9); score >= 4 -> Color(0xFFFFFFF3); score > 0 -> Color(0xFFFFF3E0); else -> Color(0xFFFAFAFA) }
                                        Box(modifier = Modifier.height(40.dp).width(64.dp).background(bgColor).border(0.5.dp, Color(0xFFE0E0E0)).clickable { editingPerson = person; editingProcess = process; currentScore = score.toString(); showEditDialog.value = true }, contentAlignment = Alignment.Center) { Text(if (score > 0) score.toString() else "", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (score >= 7) Color(0xFF2E7D32) else if (score > 0) Color(0xFFF57F17) else Color(0xFFBDBDBD)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog.value && editingPerson != null) {
        AlertDialog(onDismissRequest = { showEditDialog.value = false }, title = { Text("编辑评分") }, text = { Column { Text("${editingPerson!!.name} - $editingProcess"); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = currentScore, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) currentScore = it }, label = { Text("评分") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { viewModel.setSkillScore(editingPerson!!.id, editingProcess, currentScore.toIntOrNull() ?: 0); showEditDialog.value = false }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showEditDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 3: 工序流程（显示工序列表） ==========
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val showAddDialog = remember { mutableStateOf(false) }

    // 加载每个产品的工序列表
    var processMap by remember { mutableStateOf<Map<Int, List<String>>>(emptyMap()) }
    LaunchedEffect(products) {
        val map = mutableMapOf<Int, List<String>>()
        for (product in products) {
            val processes = repo.getProcessesOnce(product.id)
            map[product.id] = processes.map { it.processName }
        }
        processMap = map
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无产品数据", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Button(onClick = { showAddDialog.value = true }) { Text("添加产品") } }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products, key = { it.id }) { product ->
                    val processes = processMap[product.id] ?: emptyList()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(product.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("产能: ${product.capacity}  需求: ${product.requiredPeople}人", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(onClick = { viewModel.deleteProduct(product) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(36.dp)) }
                            }
                            if (processes.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("工序步骤:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                // 横向显示工序流程
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    itemsIndexed(processes) { index, process ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(vertical = 2.dp)) {
                                                Text(" $process ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                            }
                                            if (index < processes.size - 1) { Text("→", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp) }
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text("暂无工序", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showAddDialog.value = true }, modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "添加产品") }
    }
    if (showAddDialog.value) {
        var name by remember { mutableStateOf("") }
        var capacity by remember { mutableStateOf("") }
        var people by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddDialog.value = false }, title = { Text("添加产品") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("产品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = capacity, onValueChange = { if (it.all { c -> c.isDigit() }) capacity = it }, label = { Text("产能") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = people, onValueChange = { if (it.all { c -> c.isDigit() }) people = it }, label = { Text("需求人数") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { viewModel.addProduct(name.trim(), capacity.toIntOrNull() ?: 0, people.toIntOrNull() ?: 0); showAddDialog.value = false } }, enabled = name.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = { showAddDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 4: 智能排工（显示排工结果） ==========
@Composable
fun DispatchTab(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val logs by viewModel.logs.collectAsState()
    var showResult by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.executeDispatch() }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
            if (isLoading) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) } else { Icon(Icons.Default.PlayArrow, null) }
            Spacer(Modifier.width(8.dp)); Text("执行排工")
        }

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

        if (result != null && result!!.assignments.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxSize().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("排工结果", fontWeight = FontWeight.Bold)
                        Text("${result!!.assignments.size}条分配", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Divider()
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        result!!.assignments.forEach { assignment ->
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(assignment.productName, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(assignment.processName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(assignment.assignedPerson ?: "未分配", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (assignment.assignedPerson != null) Color(0xFF1976D2) else Color(0xFFC62828))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
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
