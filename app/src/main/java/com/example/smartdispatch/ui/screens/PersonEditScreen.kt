package com.example.smartdispatch.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.MainViewModel
import com.example.smartdispatch.data.entity.Person

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonEditTab(viewModel: MainViewModel) {
    val persons by viewModel.allPersons.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<Person?>(null) }

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
                    "人员列表 (${persons.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                TextButton(
                    onClick = { showAddDialog.value = true },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("+ 添加人员", fontSize = 10.sp) }
            }

            if (persons.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PeopleOutline, "无人员", modifier = Modifier.size(48.dp), tint = Color(0xFFBDBDBD))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无人员数据", color = Color(0xFFBDBDBD))
                        Spacer(Modifier.height(8.dp))
                        Text("请导入Excel或手动添加", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(persons, key = { _, p -> p.id }) { index, person ->
                        PersonItem(
                            index = index,
                            person = person,
                            viewModel = viewModel,
                            onEdit = { editingPerson = person },
                            onDelete = { viewModel.deletePerson(person) },
                            onToggleLeave = { viewModel.toggleLeaveAndDispatch(person) }
                        )
                    }
                }
            }
        }
    }

    // 添加人员对话框
    if (showAddDialog.value) {
        AddPersonDialog(
            onDismiss = { showAddDialog.value = false },
            onConfirm = { name, employeeId, jobType ->
                viewModel.addPerson(name, employeeId, jobType)
                showAddDialog.value = false
            }
        )
    }

    // 编辑人员对话框
    if (editingPerson != null) {
        EditPersonDialog(
            person = editingPerson!!,
            onDismiss = { editingPerson = null },
            onConfirm = { name, employeeId, jobType ->
                viewModel.updatePersonInfo(editingPerson!!, name, employeeId, jobType)
                editingPerson = null
            }
        )
    }
}

@Composable
private fun PersonItem(
    index: Int,
    person: Person,
    viewModel: MainViewModel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleLeave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(if (index % 2 == 0) Color.White else Color(0xFFFAFAFA))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", fontSize = 11.sp, color = Color(0xFF999999), modifier = Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                person.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (person.onLeave) Color(0xFFBDBDBD) else Color.Black
            )
            Row {
                if (person.employeeId.isNotEmpty()) {
                    Text(person.employeeId, fontSize = 9.sp, color = Color(0xFF999999))
                }
                if (person.jobType.isNotEmpty()) {
                    if (person.employeeId.isNotEmpty()) Text(" | ", fontSize = 9.sp, color = Color(0xFFDDDDDD))
                    Text(person.jobType, fontSize = 9.sp, color = Color(0xFF999999))
                }
            }
        }
        // 请假状态
        IconButton(onClick = onToggleLeave, modifier = Modifier.size(28.dp)) {
            Icon(
                if (person.onLeave) Icons.Default.Bedtime else Icons.Default.WbSunny,
                if (person.onLeave) "请假中" else "在岗",
                tint = if (person.onLeave) Color(0xFF9E9E9E) else Color(0xFFFFA726),
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(16.dp), tint = Color(0xFFFF5252))
        }
    }
}

@Composable
private fun AddPersonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var jobType by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加人员") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("工号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = jobType,
                    onValueChange = { jobType = it },
                    label = { Text("工种") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), employeeId.trim(), jobType.trim()) }) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EditPersonDialog(
    person: Person,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(person.name) }
    var employeeId by remember { mutableStateOf(person.employeeId) }
    var jobType by remember { mutableStateOf(person.jobType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑人员") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("工号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = jobType,
                    onValueChange = { jobType = it },
                    label = { Text("工种") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), employeeId.trim(), jobType.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}