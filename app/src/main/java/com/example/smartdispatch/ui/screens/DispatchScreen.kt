package com.example.smartdispatch.ui.screens

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.MainViewModel
import com.example.smartdispatch.data.entity.FixedCell
import com.example.smartdispatch.data.entity.Person
import com.example.smartdispatch.data.entity.Product
import com.example.smartdispatch.model.DispatchResult
import com.example.smartdispatch.model.ProcessAssignment

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DispatchTab(viewModel: MainViewModel, isLandscape: Boolean = false) {
    val persons by viewModel.allPersons.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val inputNames by viewModel.inputNames.collectAsState()
    val dispatchResult by viewModel.dispatchResult.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val fixedSlots by viewModel.fixedInputSlots.collectAsState()
    val fixedPeople by viewModel.fixedPeople.collectAsState()
    val matchedProducts by viewModel.matchedProducts.collectAsState()
    val focusedInputIndex by viewModel.focusedInputIndex.collectAsState()
    val allFixedCells by viewModel.allFixedCells.collectAsState()
    val recentProducts by viewModel.recentProducts.collectAsState()

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val isLandscapeMode = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFromExcel(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        uri?.let { viewModel.exportToExcel(it) }
    }

    // 排工结果按输入槽位分组
    val assignmentsByInput = viewModel.assignmentsByInput(dispatchResult, inputNames, products)

    // 日志弹窗
    val showLogDialog = remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F0FF))) {
        // 输入框区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            inputNames.forEachIndexed { index, name ->
                val isFixed = viewModel.isInputSlotFixed(index)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscapeMode) 28.dp else 36.dp)
                ) {
                    // 固定列切换按钮
                    IconButton(
                        onClick = { viewModel.toggleFixedSlot(index) },
                        modifier = Modifier.size(if (isLandscapeMode) 24.dp else 28.dp)
                    ) {
                        Icon(
                            if (isFixed) Icons.Default.PushPin else Icons.Default.PushPinOutlined,
                            "固定",
                            tint = if (isFixed) Color(0xFFFFD600) else Color.Gray,
                            modifier = Modifier.size(if (isLandscapeMode) 14.dp else 18.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f).height(if (isLandscapeMode) 26.dp else 34.dp)) {
                        BasicTextField(
                            value = name,
                            onValueChange = { viewModel.updateInputName(index, it) },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = if (isLandscapeMode) 11.sp else 14.sp,
                                color = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isFixed) Color(0xFFFFF9C4) else Color(0xFFF5F5F5),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    viewModel.clearFocus()
                                }
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (name.isEmpty()) {
                                        Text(
                                            "产品 ${index + 1}",
                                            color = Color(0xFFBDBDBD),
                                            fontSize = if (isLandscapeMode) 11.sp else 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        // 自动完成下拉列表
                        if (focusedInputIndex == index && matchedProducts.isNotEmpty()) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { viewModel.clearFocus() },
                                modifier = Modifier.fillMaxWidth(0.8f).background(Color.White)
                            ) {
                                matchedProducts.forEach { productName ->
                                    DropdownMenuItem(
                                        text = { Text(productName, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.selectProductAndDispatch(index, productName)
                                            focusManager.clearFocus()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // 排工按钮
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.clearFocus()
                            viewModel.autoDispatch()
                        },
                        modifier = Modifier.size(if (isLandscapeMode) 24.dp else 28.dp),
                        enabled = name.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "排工",
                            tint = if (name.isNotBlank()) Color(0xFF7C4DFF) else Color.Gray,
                            modifier = Modifier.size(if (isLandscapeMode) 16.dp else 20.dp)
                        )
                    }
                }
            }
        }

        // 操作按钮行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEDE8F6))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(
                onClick = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("导入", fontSize = 10.sp) }
            TextButton(
                onClick = { exportLauncher.launch("智能排工_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.xlsx") },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("导出", fontSize = 10.sp) }
            TextButton(
                onClick = { viewModel.clearLogs() },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("清空日志", fontSize = 10.sp) }
            Spacer(Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            TextButton(
                onClick = { showLogDialog.value = true },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("日志(${logs.size})", fontSize = 10.sp) }
        }

        // 排工结果表格
        if (dispatchResult != null) {
            DispatchResultTable(
                viewModel = viewModel,
                dispatchResult = dispatchResult!!,
                inputNames = inputNames,
                products = products,
                assignmentsByInput = assignmentsByInput,
                persons = persons,
                allFixedCells = allFixedCells,
                fixedSlots = fixedSlots,
                fixedPeople = fixedPeople,
                isLandscape = isLandscapeMode
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Assignment,
                        "无排工结果",
                        modifier = Modifier.size(48.dp),
                        tint = Color(0xFFBDBDBD)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("请输入产品名称并点击排工", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                }
            }
        }
    }

    // 日志弹窗
    if (showLogDialog.value) {
        AlertDialog(
            onDismissRequest = { showLogDialog.value = false },
            title = { Text("排工日志") },
            text = {
                Column {
                    logs.forEach { log ->
                        Text(log, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog.value = false }) { Text("关闭") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DispatchResultTable(
    viewModel: MainViewModel,
    dispatchResult: DispatchResult,
    inputNames: List<String>,
    products: List<Product>,
    assignmentsByInput: Map<Int, List<ProcessAssignment>>,
    persons: List<Person>,
    allFixedCells: List<FixedCell>,
    fixedSlots: Set<Int>,
    fixedPeople: Set<String>,
    isLandscape: Boolean
) {
    val fs = viewModel.fontSize
    val rh = viewModel.rowHeight
    val cw = viewModel.colWidth

    val allProcessNames = dispatchResult.assignments.map { it.processName }.distinct()
    val maxRows = dispatchResult.assignments.maxOfOrNull { it.rowIndex + 1 } ?: 0

    val horizontalScrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 统计信息
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF0EBFF))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("产品", "${assignmentsByInput.size}", MaterialTheme.colorScheme.primary)
            StatItem("分配", "${dispatchResult.assignedCount}", Color(0xFF4CAF50))
            StatItem("未分配", "${dispatchResult.unassignedCount}", Color(0xFFFF5722))
            StatItem("固定列", "${fixedPeople.size}", Color(0xFFFFD600))
        }

        // 表格
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
            Column {
                // 表头行
                Row(
                    modifier = Modifier
                        .background(Color(0xFFE8E0F0))
                        .height(rh.dp)
                ) {
                    // 序号列
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(rh.dp)
                            .border(0.5.dp, Color(0xFFD0D0D0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("#", fontSize = fs.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                    }
                    // 工序名列
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(rh.dp)
                            .border(0.5.dp, Color(0xFFD0D0D0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("工序", fontSize = fs.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                    }
                    // 每个产品的列
                    assignmentsByInput.forEach { (slotIndex, _) ->
                        val productName = inputNames.getOrElse(slotIndex) { "" }
                        val isFixed = slotIndex in fixedSlots
                        Box(
                            modifier = Modifier
                                .width(cw.dp)
                                .height(rh.dp)
                                .border(0.5.dp, Color(0xFFD0D0D0))
                                .background(if (isFixed) Color(0xFFFFF9C4) else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                productName,
                                fontSize = fs.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isFixed) Color(0xFFF57F17) else Color(0xFF333333)
                            )
                        }
                    }
                }

                // 数据行
                allProcessNames.forEachIndexed { rowIndex, processName ->
                    Row(
                        modifier = Modifier
                            .background(if (rowIndex % 2 == 0) Color.White else Color(0xFFFAFAFA))
                            .height(rh.dp)
                    ) {
                        // 序号
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .height(rh.dp)
                                .border(0.5.dp, Color(0xFFE0E0E0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${rowIndex + 1}", fontSize = fs.sp, color = Color(0xFF999999))
                        }
                        // 工序名
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(rh.dp)
                                .border(0.5.dp, Color(0xFFE0E0E0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(processName, fontSize = fs.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // 每个产品的人员
                        assignmentsByInput.forEach { (slotIndex, assignments) ->
                            val assignment = assignments.find { it.processName == processName }
                            val personName = assignment?.assignedPerson ?: ""
                            val isFixedCell = allFixedCells.any {
                                it.colIndex == slotIndex * 2 + 1 && it.rowIndex == rowIndex
                            }
                            Box(
                                modifier = Modifier
                                    .width(cw.dp)
                                    .height(rh.dp)
                                    .border(0.5.dp, Color(0xFFE0E0E0))
                                    .background(
                                        when {
                                            isFixedCell -> Color(0xFFFFF9C4)
                                            personName.isEmpty() -> Color(0xFFFFF0F0)
                                            else -> Color.Transparent
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (personName.isNotEmpty()) {
                                    Text(
                                        personName,
                                        fontSize = fs.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isFixedCell) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isFixedCell) Color(0xFFF57F17) else Color.Black
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

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 9.sp, color = Color(0xFF999999))
    }
}