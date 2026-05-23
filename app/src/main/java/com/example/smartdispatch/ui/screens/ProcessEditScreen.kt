package com.example.smartdispatch.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.DispatchApplication
import com.example.smartdispatch.MainViewModel
import com.example.smartdispatch.data.entity.Product
import com.example.smartdispatch.data.entity.ProductProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessEditScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    var processMap by remember { mutableStateOf(emptyMap<Int, List<ProductProcess>>()) }
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    var selectedProductId by remember { mutableStateOf<Int?>(null) }
    var editingProcessId by remember { mutableStateOf<Int?>(null) }
    var editName by remember { mutableStateOf("") }
    var newProcessName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑工序流程", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                if (selectedProductId == null) {
                    // 选择产品
                    products.forEach { product ->
                        TextButton(
                            onClick = { selectedProductId = product.id },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(product.name, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, "选择")
                        }
                    }
                } else {
                    val product = products.find { it.id == selectedProductId }
                    val processes = processMap[selectedProductId] ?: emptyList()

                    // 返回按钮 + 产品名
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        IconButton(onClick = { selectedProductId = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                        }
                        Text(product?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    // 工序列表
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        processes.forEach { process ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().height(36.dp).padding(vertical = 2.dp)
                            ) {
                                if (editingProcessId == process.id) {
                                    BasicTextField(
                                        value = editName,
                                        onValueChange = { editName = it },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                            onDone = {
                                                if (editName.isNotBlank()) {
                                                    viewModel.updateProcessName(selectedProductId!!, process.id, editName.trim())
                                                }
                                                editingProcessId = null
                                            }
                                        )
                                    )
                                } else {
                                    Text(
                                        process.processName,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    editingProcessId = process.id
                                    editName = process.processName
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = {
                                    viewModel.deleteProcess(process)
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(16.dp), tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }

                    // 添加工序
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = newProcessName,
                            onValueChange = { newProcessName = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (newProcessName.isEmpty()) Text("新工序名称", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                if (newProcessName.isNotBlank()) {
                                    viewModel.addProcessToProduct(selectedProductId!!, newProcessName.trim())
                                    newProcessName = ""
                                }
                            },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("添加", fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProcessFlowTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    var processMap by remember { mutableStateOf(emptyMap<Int, List<ProductProcess>>()) }
    val processVer by viewModel.processVersion.collectAsState()
    LaunchedEffect(products, processVer) {
        val map = mutableMapOf<Int, List<ProductProcess>>()
        for (product in products) { map[product.id] = repo.getProcessesOnce(product.id) }
        processMap = map
    }

    val maxProcesses = processMap.values.maxOfOrNull { it.size } ?: 0
    val scrollState = rememberScrollState()

    LaunchedEffect(maxProcesses) {
        if (scrollState.value != 0) scrollState.scrollTo(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFFF0FFF4), Color(0xFFE8F5ED))))) {
        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无产品数据", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("请先在「产品」标签页添加产品", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                }
            }
        } else {
            Column {
                // 表头
                Row(
                    modifier = Modifier
                        .background(Color(0xFFE8F5E9))
                        .height(36.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp)
                            .border(0.5.dp, Color(0xFFC8E6C9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("产品", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    }
                    for (i in 0 until maxProcesses) {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(36.dp)
                                .border(0.5.dp, Color(0xFFC8E6C9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("工序${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF558B2F))
                        }
                    }
                }

                // 数据行
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    products.forEach { product ->
                        val processes = processMap[product.id] ?: emptyList()
                        Row(
                            modifier = Modifier
                                .background(Color.White)
                                .height(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(32.dp)
                                    .border(0.5.dp, Color(0xFFE0E0E0))
                                    .background(Color(0xFFF1F8E9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    product.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            for (i in 0 until maxProcesses) {
                                val process = processes.getOrNull(i)
                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(32.dp)
                                        .border(0.5.dp, Color(0xFFE0E0E0)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (process != null) {
                                        Text(
                                            process.processName,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = Color(0xFF333333)
                                        )
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