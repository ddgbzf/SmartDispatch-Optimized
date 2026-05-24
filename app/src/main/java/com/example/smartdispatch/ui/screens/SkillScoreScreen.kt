package com.example.smartdispatch.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.DispatchApplication
import com.example.smartdispatch.MainViewModel
import com.example.smartdispatch.data.entity.Person
import com.example.smartdispatch.data.entity.SkillScore

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SkillScoreTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val processNames by viewModel.allProcessNames.collectAsState()
    val scoreVer by viewModel.scoreVersion.collectAsState()
    val repo = (LocalContext.current.applicationContext as DispatchApplication).repository

    if (persons.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("请先导入Excel或添加人员", color = MaterialTheme.colorScheme.outline)
        }
        return
    }

    val showAddProcessDialog = remember { mutableStateOf(false) }

    if (processNames.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无工序", color = Color(0xFF999999))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showAddProcessDialog.value = true }) {
                    Text("添加工序")
                }
            }
        }
    } else {
        // 评分表格
        val scrollState = rememberLazyListState()
        val focusManager = LocalFocusManager.current

        // 编辑状态
        val editingCell = remember { mutableStateOf<Pair<Int, String>?>(null) }
        val editValues = remember { mutableStateMapOf<String, String>() }

        // 搜索对话框状态
        val showSearchDialog = remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F0FF))) {
            Column {
                // 操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEDE8F6))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "技能评分表",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF666666)
                    )
                    Row {
                        TextButton(
                            onClick = { showSearchDialog.value = true },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("搜索", fontSize = 10.sp) }
                        TextButton(
                            onClick = { showAddProcessDialog.value = true },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("+工序", fontSize = 10.sp) }
                    }
                }

                // 表头
                Row(
                    modifier = Modifier
                        .background(Color(0xFFE8E0F0))
                        .height(32.dp)
                ) {
                    // 姓名列（固定）
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(32.dp)
                            .border(0.5.dp, Color(0xFFD0D0D0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("姓名", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                    }
                    // 工序列
                    processNames.forEach { processName ->
                        Box(
                            modifier = Modifier
                                .width(70.dp)
                                .height(32.dp)
                                .border(0.5.dp, Color(0xFFD0D0D0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                processName,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 数据行
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(persons, key = { _, p -> p.id }) { index, person ->
                        Row(
                            modifier = Modifier
                                .background(if (index % 2 == 0) Color.White else Color(0xFFFAFAFA))
                                .height(28.dp)
                        ) {
                            // 姓名
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(28.dp)
                                    .border(0.5.dp, Color(0xFFE0E0E0)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    person.name,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (person.onLeave) FontWeight.Light else FontWeight.Normal,
                                    color = if (person.onLeave) Color(0xFFBDBDBD) else Color.Black
                                )
                            }
                            // 评分
                            processNames.forEach { processName ->
                                val cellKey = "${person.id}_$processName"
                                val isEditing = editingCell.value == Pair(person.id, processName)
                                val score = editValues[cellKey]?.toIntOrNull()
                                    ?: repo.getScoreOnce(person.id, processName)

                                Box(
                                    modifier = Modifier
                                        .width(70.dp)
                                        .height(28.dp)
                                        .border(0.5.dp, Color(0xFFE0E0E0))
                                        .clickable {
                                            editValues[cellKey] = score.toString()
                                            editingCell.value = Pair(person.id, processName)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isEditing) {
                                        BasicTextField(
                                            value = editValues[cellKey] ?: score.toString(),
                                            onValueChange = { editValues[cellKey] = it.filter { c -> c.isDigit() } },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                fontSize = 11.sp,
                                                color = Color.Black,
                                                textAlign = TextAlign.Center
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    val v = editValues[cellKey]?.toIntOrNull()
                                                    if (v != null && v in 0..100) {
                                                        viewModel.setSkillScore(person.id, processName, v)
                                                    }
                                                    editingCell.value = null
                                                    focusManager.clearFocus()
                                                }
                                            ),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFFE3F2FD))
                                                .padding(horizontal = 4.dp)
                                                .onFocusChanged { focusState ->
                                                    if (!focusState.isFocused && isEditing) {
                                                        val v = editValues[cellKey]?.toIntOrNull()
                                                        if (v != null && v in 0..100) {
                                                            viewModel.setSkillScore(person.id, processName, v)
                                                        }
                                                        editingCell.value = null
                                                    }
                                                },
                                            decorationBox = { innerTextField ->
                                                Box(contentAlignment = Alignment.Center) {
                                                    innerTextField()
                                                }
                                            }
                                        )
                                    } else {
                                        Text(
                                            if (score > 0) "$score" else "-",
                                            fontSize = 11.sp,
                                            color = if (score > 0) Color.Black else Color(0xFFBDBDBD),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 搜索对话框
        if (showSearchDialog.value) {
            var searchPerson by remember { mutableStateOf("") }
            var searchProcess by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSearchDialog.value = false },
                title = { Text("搜索定位") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = searchPerson,
                            onValueChange = { searchPerson = it },
                            label = { Text("人员姓名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchProcess,
                            onValueChange = { searchProcess = it },
                            label = { Text("工序名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 查找并滚动到目标位置
                        val personIndex = persons.indexOfFirst { it.name.contains(searchPerson, ignoreCase = true) }
                        if (personIndex >= 0) {
                            kotlinx.coroutines.MainScope().launch {
                                scrollState.animateScrollToItem(personIndex)
                            }
                        }
                        showSearchDialog.value = false
                    }) { Text("定位") }
                },
                dismissButton = {
                    TextButton(onClick = { showSearchDialog.value = false }) { Text("取消") }
                }
            )
        }
    }

    // 添加工序对话框
    if (showAddProcessDialog.value) {
        var newProcessName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddProcessDialog.value = false },
            title = { Text("添加工序") },
            text = {
                OutlinedTextField(
                    value = newProcessName,
                    onValueChange = { newProcessName = it },
                    label = { Text("工序名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProcessName.isNotBlank()) {
                        viewModel.addScoreProcess(newProcessName.trim())
                        showAddProcessDialog.value = false
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddProcessDialog.value = false }) { Text("取消") }
            }
        )
    }
}