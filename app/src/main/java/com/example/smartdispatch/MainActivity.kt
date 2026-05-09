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
    private val _scoreVersion = MutableStateFlow(0)
    val scoreVersion: StateFlow<Int> = _scoreVersion.asStateFlow()

    fun addLog(msg: String) { _logs.update { it + msg } }
    fun clearLogs() { _logs.update { emptyList() } }
    fun addPerson(name: String) = viewModelScope.launch { repo.addPerson(name) }
    fun toggleLeave(person: Person) = viewModelScope.launch { repo.updatePerson(person.copy(onLeave = !person.onLeave)) }
    fun deletePerson(person: Person) = viewModelScope.launch { repo.deletePerson(person) }

    fun setSkillScore(personId: Int, processName: String, score: Int) = viewModelScope.launch {
        repo.setSkillScore(personId, processName, score)
        _scoreVersion.update { it + 1 }
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
        } catch (e: Exception) { addLog("❌ 错误: ${e.message}") }
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

// ========== Tab 2: 工序评分（整体联动滚动） ==========
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

    // 用一个共享的横向滚动状态，所有行联动
    val scrollState = rememberScrollState()
    // 共享垂直滚动状态，固定列和数据区域同步
    val lazyListState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text("人员技能评分（点击单元格编辑）", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        // 固定表头 + 联动滚动内容
        Box(modifier = Modifier.fillMaxSize()) {
            // 固定姓名列（背景遮挡滚动内容）
            Column(modifier = Modifier.width(72.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                Box(modifier = Modifier.height(36.dp).width(72.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("姓名", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                LazyColumn(modifier = Modifier.fillMaxWidth(), state = lazyListState) {
                    items(persons, key = { it.id }) { person ->
                        Box(modifier = Modifier.height(40.dp).fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            // 整体联动的评分区域
            Row(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 表头行（和内容同步滚动）
                    Row(modifier = Modifier.horizontalScroll(scrollState).fillMaxWidth()) {
                        processNames.forEach { process ->
                            Box(modifier = Modifier.width(64.dp).height(36.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(process, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    // 数据行（和表头同步滚动，和姓名列同步垂直滚动）
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                        items(persons, key = { it.id }) { person ->
                            Row(modifier = Modifier.horizontalScroll(scrollState).fillMaxWidth()) {
                                processNames.forEach { process ->
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
    }

    if (showEditDialog.value && editingPerson != null) {
        AlertDialog(onDismissRequest = { showEditDialog.value = false }, title = { Text("编辑评分") }, text = { Column { Text("${editingPerson!!.name} - $editingProcess"); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = currentScore, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) currentScore = it }, label = { Text("评分") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { viewModel.setSkillScore(editingPerson!!.id, editingProcess, currentScore.toIntOrNull() ?: 0); scoreMap = scoreMap.toMutableMap().apply { put(Pair(editingPerson!!.id, editingProcess), currentScore.toIntOrNull() ?: 0) }; showEditDialog.value = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showEditDialog.value = false }) { Text("取消") } })
    }
}

// ========== Tab 3: 工序流程（横向表格，和原表一样） ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository
    val showAddProductDialog = remember { mutableStateOf(false) }
    val showAddProcessDialog = remember { mutableStateOf(false) }

    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    LaunchedEffect(products) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    // 计算最大工序数
    val maxProcesses = processMap.values.maxOfOrNull { it.size } ?: 0

    // 共享横向滚动状态，表头和数据行联动
    val scrollState = rememberScrollState()
    // 共享垂直滚动状态，固定列和数据区域同步
    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无产品数据", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Button(onClick = { showAddProductDialog.value = true }) { Text("添加产品") } }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 固定列（型号名称、产能、人数）
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row {
                        Box(modifier = Modifier.width(120.dp).height(36.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("型号名称", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Box(modifier = Modifier.width(60.dp).height(36.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("产能", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Box(modifier = Modifier.width(50.dp).height(36.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("人数", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
                // 可滚动的工序列区域
                Row(modifier = Modifier.fillMaxSize()) {
                    // 固定列区域
                    LazyColumn(modifier = Modifier.width(230.dp).fillMaxHeight(), state = lazyListState) {
                        items(products, key = { it.id }) { product ->
                            Row(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                Box(modifier = Modifier.width(120.dp).height(44.dp).border(0.5.dp, Color(0xFFE0E0E0)).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { Text(product.name, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                Box(modifier = Modifier.width(60.dp).height(44.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.capacity.toString(), fontSize = 12.sp) }
                                Box(modifier = Modifier.width(50.dp).height(44.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(product.requiredPeople.toString(), fontSize = 12.sp) }
                            }
                        }
                    }
                    // 可横向滚动的工序区域
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 工序表头（和内容同步横向滚动）
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).background(MaterialTheme.colorScheme.primaryContainer)) {
                            repeat(maxProcesses) { i ->
                                Box(modifier = Modifier.width(72.dp).height(36.dp), contentAlignment = Alignment.Center) { Text("工序${i + 1}", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                            }
                            Box(modifier = Modifier.width(60.dp).height(36.dp)) {} // 操作列
                        }
                        Divider()
                        // 工序数据行（和表头同步横向滚动，和固定列同步垂直滚动）
                        LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                            items(products, key = { it.id }) { product ->
                                val processes = processMap[product.id] ?: emptyList()
                                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                                    for (i in 0 until maxProcesses) {
                                        val pp = processes.getOrNull(i)
                                        Box(modifier = Modifier.width(72.dp).height(44.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                            if (pp != null) {
                                                Text(pp.processName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    Box(modifier = Modifier.width(60.dp).height(44.dp), contentAlignment = Alignment.Center) {
                                        IconButton(onClick = { viewModel.deleteProduct(product) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(16.dp)) }
                                    }
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
}

// ========== Tab 4: 智能排工（电子表格布局，和原表一样） ==========
@Composable
fun DispatchTab(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val persons by viewModel.allPersons.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    var processMap by remember { mutableStateOf<Map<Int, List<ProductProcess>>>(emptyMap()) }
    LaunchedEffect(products) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    // 型号名称输入框
    var searchName by remember { mutableStateOf("") }
    // 根据搜索过滤产品
    val filteredProducts = if (searchName.isBlank()) products else products.filter { it.name.contains(searchName.trim(), ignoreCase = true) }

    // 按产品分组排工结果
    val groupedAssignments = result?.assignments?.groupBy { it.productName } ?: emptyMap()

    // 共享横向滚动状态
    val scrollState = rememberScrollState()
    // 共享垂直滚动状态
    val lazyListState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部操作栏
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchName,
                onValueChange = { searchName = it },
                label = { Text("型号名称", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入型号名称筛选", fontSize = 12.sp) }
            )
            Button(onClick = { viewModel.executeDispatch() }, enabled = !isLoading) {
                if (isLoading) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) } else { Icon(Icons.Default.PlayArrow, null) }
                Spacer(Modifier.width(6.dp)); Text("执行排工", fontSize = 14.sp)
            }
        }

        // 统计栏
        result?.let { r ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总人数", r.totalPeople.toString())
                StatItem("请假", r.leaveCount.toString())
                StatItem("已分配", r.assignedCount.toString())
                StatItem(if (r.remainingCount >= 0) "剩余" else "欠缺", kotlin.math.abs(r.remainingCount).toString(), if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
            }
        }

        Divider()

        // 电子表格布局
        Box(modifier = Modifier.fillMaxSize()) {
            // 固定请假人员列
            Column(modifier = Modifier.width(72.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                Box(modifier = Modifier.height(36.dp).width(72.dp).background(Color(0xFFBBDEFB)), contentAlignment = Alignment.Center) { Text("请假人员", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                LazyColumn(modifier = Modifier.fillMaxWidth(), state = lazyListState) {
                    val leavePeople = persons.filter { it.onLeave }
                    items(leavePeople) { person ->
                        Box(modifier = Modifier.height(40.dp).fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Text(person.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            // 可横向滚动的产品列区域
            Row(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 表头行（和数据行同步横向滚动）
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).background(Color(0xFFBBDEFB))) {
                        filteredProducts.forEach { product ->
                            Box(modifier = Modifier.width(80.dp).height(36.dp), contentAlignment = Alignment.Center) { Text(product.name, fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        // 状态列
                        Box(modifier = Modifier.width(72.dp).height(36.dp), contentAlignment = Alignment.Center) { Text("状态", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    }
                    Divider()
                    // 数据行（和表头同步横向滚动，和请假列同步垂直滚动）
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                        // 计算最大行数
                        val maxRows = maxOf(
                            filteredProducts.maxOfOrNull { product ->
                                val processes = processMap[product.id] ?: emptyList()
                                val assignments = groupedAssignments[product.name] ?: emptyList()
                                maxOf(processes.size, assignments.size)
                            } ?: 0,
                            1
                        )

                        items(maxRows) { rowIndex ->
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                                filteredProducts.forEach { product ->
                                    val processes = processMap[product.id] ?: emptyList()
                                    val assignments = groupedAssignments[product.name] ?: emptyList()
                                    val processName = processes.getOrNull(rowIndex)?.processName ?: ""
                                    val assignedPerson = assignments.getOrNull(rowIndex)?.assignedPerson ?: ""

                                    Box(modifier = Modifier.width(80.dp).height(40.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                        Column {
                                            if (processName.isNotEmpty()) { Text(processName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF666666)) }
                                            if (assignedPerson.isNotEmpty()) { Text(assignedPerson, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1976D2)) }
                                        }
                                    }
                                }
                                // 状态列
                                Box(modifier = Modifier.width(72.dp).height(40.dp).border(0.5.dp, Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                    if (rowIndex == 0) {
                                        Text(result?.statusMessage ?: "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if ((result?.remainingCount ?: 0) >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                                    } else {
                                        val unassigned = result?.unassignedPeople ?: emptyList()
                                        val person = unassigned.getOrNull(rowIndex - 1)
                                        if (person != null) { Text(person, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
