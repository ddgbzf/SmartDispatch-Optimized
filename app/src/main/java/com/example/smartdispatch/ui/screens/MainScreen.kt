package com.example.smartdispatch.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartdispatch.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedTab by remember { mutableIntStateOf(0) }
    val showSettings = remember { mutableStateOf(false) }
    val showHelp = remember { mutableStateOf(false) }
    val showProcessEdit = remember { mutableStateOf(false) }
    val showFixedColumn = remember { mutableStateOf(false) }

    val tabs = listOf(
        TabItem("排工", Icons.Default.Assignment, 0),
        TabItem("评分", Icons.Default.Star, 1),
        TabItem("流程", Icons.Default.AccountTree, 2),
        TabItem("人员", Icons.Default.People, 3),
        TabItem("产品", Icons.Default.Inventory2, 4),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "智能排工",
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isLandscape) 14.sp else 18.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showFixedColumn.value = true }) {
                        Icon(Icons.Default.PushPin, "固定列", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showProcessEdit.value = true }) {
                        Icon(Icons.Default.Edit, "编辑工序")
                    }
                    IconButton(onClick = { showHelp.value = true }) {
                        Icon(Icons.Default.HelpOutline, "帮助")
                    }
                    IconButton(onClick = { showSettings.value = true }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFEDE8F6)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab.index,
                        onClick = { selectedTab = tab.index },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> DispatchTab(viewModel, isLandscape)
                1 -> SkillScoreTab(viewModel)
                2 -> ProcessFlowTab(viewModel)
                3 -> PersonEditTab(viewModel)
                4 -> ProductEditTab(viewModel)
            }
        }
    }

    // 对话框
    if (showSettings.value) {
        SettingsScreen(viewModel, onDismiss = { showSettings.value = false })
    }
    if (showHelp.value) {
        HelpScreen(onDismiss = { showHelp.value = false })
    }
    if (showProcessEdit.value) {
        ProcessEditScreen(viewModel, onDismiss = { showProcessEdit.value = false })
    }
    if (showFixedColumn.value) {
        FixedColumnScreen(viewModel, onDismiss = { showFixedColumn.value = false })
    }
}

private data class TabItem(val label: String, val icon: ImageVector, val index: Int)