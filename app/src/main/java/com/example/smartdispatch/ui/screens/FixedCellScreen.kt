package com.example.smartdispatch.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.MainViewModel
import com.example.smartdispatch.data.entity.FixedCell
import com.example.smartdispatch.data.entity.Person
import com.example.smartdispatch.data.entity.Product
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedCellScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val persons by viewModel.allPersons.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val fixedCells by viewModel.allFixedAssignments.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    var showAddDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固定单元格", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text("选择产品，将该产品的所有人员固定到各自单元格", fontSize = 11.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(8.dp))
                
                // 现有固定关系列表
                Text("已固定 (${fixedCells.size})", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Color(0xFF666666))
                Spacer(Modifier.height(4.dp))
                
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (fixedCells.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                Text("暂无固定关系", color = Color(0xFF999999))
                            }
                        }
                    } else {
                        items(fixedCells.size) { index ->
                            val fc = fixedCells[index]
                            val person = persons.find { it.id == fc.personId }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, null, tint = Color(0xFFF57C00), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(person?.name ?: "未知人员", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                    Text("行${fc.rowIndex} 列${fc.colIndex}", fontSize = 11.sp, color = Color(0xFF666666))
                                }
                                IconButton(onClick = { viewModel.removeFixedCell(fc.rowIndex, fc.colIndex) }) {
                                    Icon(Icons.Default.Delete, null, tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                                }
                            }
                            if (index < fixedCells.size - 1) Divider()
                        }
                    }
                }
                
                // 添加按钮
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("固定产品")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } }
    )
    
    // 选择产品对话框
    if (showAddDialog) {
        var selectedProduct by remember { mutableStateOf<Product?>(null) }
        var searchText by remember { mutableStateOf("") }
        val filteredProducts = products.filter { it.name.contains(searchText, ignoreCase = true) }
        
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("选择要固定的产品") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchText, onValueChange = { searchText = it },
                        placeholder = { Text("搜索产品") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(filteredProducts.size) { index ->
                            val product = filteredProducts[index]
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedProduct = product }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedProduct == product, onClick = { selectedProduct = product })
                                Text(product.name, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedProduct != null) {
                            coroutineScope.launch {
                                // 从上次排工结果中读取该产品的所有分配
                                viewModel.fixProductCells(selectedProduct!!.name, -1)
                            }
                            showAddDialog = false
                        }
                    },
                    enabled = selectedProduct != null
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }
}
