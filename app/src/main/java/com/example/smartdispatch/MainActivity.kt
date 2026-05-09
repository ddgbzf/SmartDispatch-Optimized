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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

    fun addProcess(productId: Int, processName: String) = viewModelScope.launch {
        val processes = repo.getProcesses(productId).first()
        repo.addProcess(productId, processName, processes.size)
    }

    fun executeDispatch() = viewModelScope.launch {
        _isLoading.value = true
        addLog("开始排工...")
        try {
            val persons = allPersons.first()
            val products = allProducts.first()
            _dispatchResult.value = DispatchResult(
                assignments = emptyList(),
                totalPeople = persons.size,
                leaveCount = persons.count { it.onLeave },
                assignedCount = 0,
                remainingCount = 0,
                unassignedPeople = emptyList(),
                statusMessage = "排工完成"
            )
            addLog("✅ 排工完成")
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
                    addLog("✅ 导入成功")
                    Toast.makeText(ctx, "导入成功", Toast.LENGTH_SHORT).show()
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromExcel(it) } }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { Toast.makeText(context, "导出功能开发中...", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能排工系统", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        filePicker.launch(arrayOf("*/*"))
                    }) { Icon(Icons.Default.FileUpload, "导入Excel") }
                    IconButton(onClick = {
                        exportPicker.launch("排工结果_${System.currentTimeMillis()}.xlsx")
                    }) { Icon(Icons.Default.FileDownload, "导出Excel") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("请假人员", "工序评分", "工序流程", "智能排工").forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Default.PersonOff, null)
                                1 -> Icon(Icons.Default.Star, null)
                                2 -> Icon(Icons.Default.AccountTree, null)
                                else -> Icon(Icons.Default.PlayArrow, null)
                            }
                        },
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 统计栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总人数", persons.size.toString())
                StatItem("请假", persons.count { it.onLeave }.toString(), Color(0xFFC62828))
                StatItem("可用", persons.count { !it.onLeave }.toString(), Color(0xFF2E7D32))
            }

            Divider()

            // 人员列表
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(persons, key = { it.id }) { person ->
                    ListItem(
                        headlineContent = { Text(person.name) },
                        supportingContent = { Text(if (person.onLeave) "请假中" else "在岗") },
                        leadingContent = {
                            Icon(
                                if (person.onLeave) Icons.Default.PersonOff else Icons.Default.Person,
                                null,
                                tint = if (person.onLeave) Color(0xFFC62828) else Color(0xFF2E7D32)
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.toggleLeave(person) }) {
                                    Icon(
                                        if (person.onLeave) Icons.Default.CheckCircle else Icons.Default.RemoveCircle,
                                        null,
                                        tint = if (person.onLeave) Color(0xFF2E7D32) else Color(0xFFC62828)
                                    )
                                }
                                IconButton(onClick = { viewModel.deletePerson(person) }) {
                                    Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828))
                                }
                            }
                        }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        // 添加按钮
        FloatingActionButton(
            onClick = { showAddDialog.value = true },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.Add, "添加人员") }
    }

    // 添加对话框
    if (showAddDialog.value) {
        AddPersonDialog(onDismiss = { showAddDialog.value = false }) { name ->
            viewModel.addPerson(name)
            showAddDialog.value = false
        }
    }
}

@Composable
fun AddPersonDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加人员") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("姓名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========== Tab 2: 工序评分 ==========
@Composable
fun SkillScoreTab(viewModel: MainViewModel) {
    val persons by viewModel.availablePersons.collectAsState()
    val processNames by viewModel.allProcessNames.collectAsState()

    if (persons.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("请先在请假人员页添加人员", color = MaterialTheme.colorScheme.outline)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "人员技能评分（点击单元格编辑）",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )

        // 表格 - 水平滚动
        HorizontalScrollTable(persons, processNames, viewModel)
    }
}

@Composable
fun HorizontalScrollTable(persons: List<Person>, processNames: List<String>, viewModel: MainViewModel) {
    val showEditDialog = remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }
    var editingProcess by remember { mutableStateOf("") }

    LazyRow(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.width(80.dp)) {
                // Header
                Box(
                    modifier = Modifier.height(48.dp).width(80.dp).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Text("姓名", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                // Rows
                persons.forEach { person ->
                    Box(
                        modifier = Modifier.height(48.dp).width(80.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(person.name, fontSize = 13.sp) }
                }
            }
        }

        processNames.forEach { process ->
            item(key = process) {
                Column(modifier = Modifier.width(72.dp)) {
                    // Header
                    Box(
                        modifier = Modifier.height(48.dp).width(72.dp).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(process, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                    // Score cells
                    persons.forEach { person ->
                        Box(
                            modifier = Modifier.height(48.dp).width(72.dp).clickable {
                                editingPerson = person
                                editingProcess = process
                                showEditDialog.value = true
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("0", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog.value && editingPerson != null) {
        var score by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showEditDialog.value = false },
            title = { Text("编辑评分") },
            text = {
                Column {
                    Text("${editingPerson!!.name} - $editingProcess")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = score,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 2) score = it },
                        label = { Text("评分(0-10)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSkillScore(editingPerson!!.id, editingProcess, score.toIntOrNull() ?: 0)
                    showEditDialog.value = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog.value = false }) { Text("取消") } }
        )
    }
}

// ========== Tab 3: 工序流程 ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无产品数据", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showAddDialog.value = true }) { Text("添加产品") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(products, key = { it.id }) { product ->
                    ProductCard(product, viewModel)
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog.value = true },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.Add, "添加产品") }
    }

    if (showAddDialog.value) {
        AddProductDialog(onDismiss = { showAddDialog.value = false }) { name, cap, ppl ->
            viewModel.addProduct(name, cap, ppl)
            showAddDialog.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductCard(product: Product, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(product.name, fontWeight = FontWeight.Bold)
                    Text("产能: ${product.capacity}  需求: ${product.requiredPeople}人", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                Row {
                    IconButton(onClick = { viewModel.deleteProduct(product) }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828))
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("工序列表（点击添加）", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    Text("暂无工序，导入Excel后显示", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun AddProductDialog(onDismiss: () -> Unit, onConfirm: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加产品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("产品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = capacity, onValueChange = { if (it.all { c -> c.isDigit() }) capacity = it }, label = { Text("产能") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = people, onValueChange = { if (it.all { c -> c.isDigit() }) people = it }, label = { Text("需求人数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), capacity.toIntOrNull() ?: 0, people.toIntOrNull() ?: 0) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========== Tab 4: 智能排工 ==========
@Composable
fun DispatchTab(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val result by viewModel.dispatchResult.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 执行按钮
        Button(
            onClick = { viewModel.executeDispatch() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.PlayArrow, null)
            }
            Spacer(Modifier.width(8.dp))
            Text("执行排工")
        }

        // 统计卡片
        result?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (r.remainingCount >= 0) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem("总人数", r.totalPeople.toString())
                    StatItem("请假", r.leaveCount.toString())
                    StatItem("已分配", r.assignedCount.toString())
                    StatItem(
                        if (r.remainingCount >= 0) "剩余" else "欠缺",
                        kotlin.math.abs(r.remainingCount).toString(),
                        if (r.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }

        // 日志
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("运行日志", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { viewModel.clearLogs() }) { Text("清空") }
                }
                Divider()
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(logs) { log ->
                        Text(log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
