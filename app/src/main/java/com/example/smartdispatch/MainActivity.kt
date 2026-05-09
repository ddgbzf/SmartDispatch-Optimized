package com.example.smartdispatch

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import com.example.smartdispatch.engine.DispatchEngine
import com.example.smartdispatch.model.DispatchResult
import com.example.smartdispatch.model.ProcessAssignment
import com.example.smartdispatch.ui.theme.智能排工Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            智能排工Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DispatchApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var dispatchResult by remember { mutableStateOf<DispatchResult?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    
    val engine = remember { DispatchEngine() }
    
    // 文件选择器 - 支持多种Excel MIME类型
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            val fileName = uri.lastPathSegment ?: "未知文件"
            logs = logs + "已选择文件: $fileName"
            
            // 检查文件类型
            val mimeType = context.contentResolver.getType(uri)
            logs = logs + "文件类型: ${mimeType ?: "未知"}"
            
            if (mimeType == null || (!mimeType.contains("spreadsheet") && !mimeType.contains("excel"))) {
                logs = logs + "⚠️ 警告: 文件可能不是Excel格式"
            }
        } else {
            logs = logs + "❌ 未选择文件"
        }
    }
    
    // 导出文件选择器
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let { exportUri ->
            scope.launch {
                isLoading = true
                logs = logs + "正在导出..."
                try {
                    selectedFileUri?.let { inputUri ->
                        val input = context.contentResolver.openInputStream(inputUri)
                        val output = context.contentResolver.openOutputStream(exportUri)
                        if (input != null && output != null) {
                            val success = engine.exportToExcel(input, output, dispatchResult!!)
                            if (success) {
                                logs = logs + "导出成功！"
                                Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
                            }
                            input.close()
                            output.close()
                        }
                    }
                } catch (e: Exception) {
                    logs = logs + "导出失败: ${e.message}"
                }
                isLoading = false
            }
        }
    }
    
    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "需要存储权限才能操作文件", Toast.LENGTH_LONG).show()
        }
    }
    
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "智能排工系统",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 操作按钮区
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 选择文件按钮
                        Button(
                            onClick = { 
                                // 支持多种Excel MIME类型
                                filePicker.launch(arrayOf(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-excel",
                                    "application/octet-stream",
                                    "*/*"
                                )) 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("选择Excel")
                        }
                        
                        // 执行排工按钮
                        Button(
                            onClick = {
                                if (selectedFileUri == null) {
                                    Toast.makeText(context, "请先选择Excel文件", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    isLoading = true
                                    logs = logs + "开始加载Excel..."
                                    try {
                                        val input = context.contentResolver.openInputStream(selectedFileUri!!)
                                        if (input != null) {
                                            val (success, errorMsg) = withContext(Dispatchers.IO) {
                                                engine.loadFromExcel(input)
                                            }
                                            if (success) {
                                                logs = logs + "✅ 数据加载成功，开始排工..."
                                                val result = withContext(Dispatchers.IO) {
                                                    engine.executeDispatch()
                                                }
                                                dispatchResult = result
                                                logs = logs + "✅ 排工完成！${result.statusMessage}"
                                                Toast.makeText(context, "排工完成！", Toast.LENGTH_SHORT).show()
                                            } else {
                                                logs = logs + "❌ 数据加载失败: $errorMsg"
                                                Toast.makeText(context, "加载失败: $errorMsg", Toast.LENGTH_LONG).show()
                                            }
                                            input.close()
                                        } else {
                                            logs = logs + "❌ 无法打开文件"
                                            Toast.makeText(context, "无法打开文件", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        logs = logs + "❌ 错误: ${e.message}"
                                        Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && selectedFileUri != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("执行排工")
                        }
                    }
                    
                    // 导出按钮
                    Button(
                        onClick = {
                            dispatchResult?.let {
                                exportPicker.launch("智能排工结果_${System.currentTimeMillis()}.xlsx")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = dispatchResult != null && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导出结果")
                    }
                }
            }
            
            // 统计信息
            dispatchResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.remainingCount >= 0) 
                            Color(0xFFE8F5E9) 
                        else 
                            Color(0xFFFFEBEE)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("总人数", result.totalPeople.toString())
                        StatItem("请假", result.leaveCount.toString())
                        StatItem("已分配", result.assignedCount.toString())
                        StatItem(
                            label = if (result.remainingCount >= 0) "剩余" else "欠缺",
                            value = kotlin.math.abs(result.remainingCount).toString(),
                            valueColor = if (result.remainingCount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            }
            
            // 结果展示
            dispatchResult?.let { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Tab栏
                        var selectedTab by remember { mutableStateOf(0) }
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("排工结果") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("未分配人员") }
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("日志") }
                            )
                        }
                        
                        when (selectedTab) {
                            0 -> AssignmentList(result.assignments)
                            1 -> UnassignedList(result.unassignedPeople)
                            2 -> LogList(logs)
                        }
                    }
                }
            }
            
            // 空状态提示
            if (dispatchResult == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "请选择Excel文件开始排工",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AssignmentList(assignments: List<ProcessAssignment>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(assignments) { assignment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            assignment.productName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            assignment.processName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        assignment.assignedPerson ?: "未分配",
                        fontWeight = FontWeight.Medium,
                        color = if (assignment.assignedPerson != null) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun UnassignedList(people: List<String>) {
    if (people.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("所有人员已分配", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(people) { person ->
                ListItem(
                    headlineContent = { Text(person) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun LogList(logs: List<String>) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        logs.forEach { log ->
            Text(
                log,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
