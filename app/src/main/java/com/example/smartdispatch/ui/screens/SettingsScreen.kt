package com.example.smartdispatch.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartdispatch.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示设置", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // 字体大小
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("字体大小", fontSize = 13.sp, modifier = Modifier.width(80.dp))
                    IconButton(onClick = { viewModel.adjustFontSize(-1f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Remove, "减小", modifier = Modifier.size(16.dp))
                    }
                    Text("${viewModel.fontSize.toInt()}", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { viewModel.adjustFontSize(1f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, "增大", modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 行高
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("行高", fontSize = 13.sp, modifier = Modifier.width(80.dp))
                    IconButton(onClick = { viewModel.adjustRowHeight(-2f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Remove, "减小", modifier = Modifier.size(16.dp))
                    }
                    Text("${viewModel.rowHeight}", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { viewModel.adjustRowHeight(2f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, "增大", modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 列宽
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("列宽", fontSize = 13.sp, modifier = Modifier.width(80.dp))
                    IconButton(onClick = { viewModel.adjustColWidth(-5f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Remove, "减小", modifier = Modifier.size(16.dp))
                    }
                    Text("${viewModel.colWidth}", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { viewModel.adjustColWidth(5f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, "增大", modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("提示：字体/行高/列宽调整后立即生效", fontSize = 10.sp, color = Color(0xFF999999))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun HelpScreen(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用帮助", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                HelpItem("1. 导入数据", "点击「导入」按钮选择Excel文件，自动导入产品、人员、工序和评分数据")
                HelpItem("2. 输入产品", "在输入框输入产品名称，支持自动完成和最近使用记录")
                HelpItem("3. 固定列", "点击图钉按钮固定某列，排工时该列人员保持不变")
                HelpItem("4. 排工", "点击播放按钮自动分配人员到各工序")
                HelpItem("5. 评分", "在「评分」标签页为每个人员设置各工序的技能评分(0-100)")
                HelpItem("6. 工序编辑", "在「流程」标签页或点击编辑按钮管理各产品的工序流程")
                HelpItem("7. 导出数据", "点击「导出」按钮将当前数据导出为Excel文件")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun HelpItem(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(desc, fontSize = 11.sp, color = Color(0xFF666666))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedColumnScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val inputNames by viewModel.inputNames.collectAsState()
    val fixedSlots by viewModel.fixedInputSlots.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固定列管理", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("点击切换固定状态：固定列的人员在重新排工时保持不变", fontSize = 11.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(8.dp))
                inputNames.forEachIndexed { index, name ->
                    val isFixed = viewModel.isInputSlotFixed(index)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Icon(
                            if (isFixed) Icons.Default.PushPin else Icons.Default.PushPinOutlined,
                            "固定",
                            tint = if (isFixed) Color(0xFFFFD600) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (name.isNotEmpty()) name else "产品 ${index + 1}",
                            fontSize = 13.sp,
                            color = if (name.isNotEmpty()) Color.Black else Color(0xFFBDBDBD),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isFixed,
                            onCheckedChange = { viewModel.toggleFixedSlot(index) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}