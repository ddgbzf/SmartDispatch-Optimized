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
import com.example.smartdispatch.data.entity.Product

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditTab(viewModel: MainViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val showAddDialog = remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var editName by remember { mutableStateOf("") }
    var editCapacity by remember { mutableStateOf("") }
    var editPeople by remember { mutableStateOf("") }

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
                    "产品列表 (${products.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                TextButton(
                    onClick = { showAddDialog.value = true },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("+ 添加产品", fontSize = 10.sp) }
            }

            if (products.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inventory2, "无产品", modifier = Modifier.size(48.dp), tint = Color(0xFFBDBDBD))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无产品数据", color = Color(0xFFBDBDBD))
                        Spacer(Modifier.height(8.dp))
                        Text("请导入Excel或手动添加", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(products, key = { _, p -> p.id }) { index, product ->
                        if (editingProduct?.id == product.id) {
                            // 编辑模式
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE3F2FD))
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                                    IconButton(onClick = { editingProduct = null }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                                    }
                                    Text(product.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(24.dp)) {
                                    Text("产能:", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                                    BasicTextField(
                                        value = editCapacity,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) editCapacity = it },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 10.sp, color = Color.Black),
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color.White, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("人数:", fontSize = 10.sp)
                                    BasicTextField(
                                        value = editPeople,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) editPeople = it },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 10.sp, color = Color.Black),
                                        modifier = Modifier
                                            .width(40.dp)
                                            .background(Color.White, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            viewModel.updateProduct(product.copy(
                                                name = editName.ifBlank { product.name },
                                                capacity = editCapacity.toIntOrNull() ?: product.capacity,
                                                requiredPeople = editPeople.toIntOrNull() ?: product.requiredPeople
                                            ))
                                            editingProduct = null
                                        },
                                        modifier = Modifier.height(24.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("保存", fontSize = 9.sp) }
                                }
                            }
                        } else {
                            ProductItem(
                                index = index,
                                product = product,
                                onEdit = {
                                    editingProduct = product
                                    editName = product.name
                                    editCapacity = product.capacity.toString()
                                    editPeople = product.requiredPeople.toString()
                                },
                                onDelete = { viewModel.deleteProduct(product) },
                                onToggleFixed = { viewModel.toggleProductFixed(product) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加产品对话框
    if (showAddDialog.value) {
        AddProductDialog(
            onDismiss = { showAddDialog.value = false },
            onConfirm = { name, capacity, people ->
                viewModel.addProduct(name, capacity, people)
                showAddDialog.value = false
            }
        )
    }
}

@Composable
private fun ProductItem(
    index: Int,
    product: Product,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFixed: () -> Unit
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
            Text(product.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text("产能: ${product.capacity}", fontSize = 9.sp, color = Color(0xFF999999))
                Text(" | 需${product.requiredPeople}人", fontSize = 9.sp, color = Color(0xFF999999))
                if (product.isFixed) {
                    Text(" | 固定", fontSize = 9.sp, color = Color(0xFFFFA726))
                }
            }
        }
        IconButton(onClick = onToggleFixed, modifier = Modifier.size(28.dp)) {
            Icon(
                if (product.isFixed) Icons.Default.Lock else Icons.Default.LockOpen,
                if (product.isFixed) "固定" else "未固定",
                tint = if (product.isFixed) Color(0xFFFFA726) else Color(0xFFBDBDBD),
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
private fun AddProductDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加产品") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("产品名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { if (it.all { c -> c.isDigit() }) capacity = it },
                    label = { Text("产能") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = people,
                    onValueChange = { if (it.all { c -> c.isDigit() }) people = it },
                    label = { Text("所需人数") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(name.trim(), capacity.toIntOrNull() ?: 0, people.toIntOrNull() ?: 0)
                }
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}