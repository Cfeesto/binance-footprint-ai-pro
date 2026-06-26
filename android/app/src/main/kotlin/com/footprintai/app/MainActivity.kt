package com.footprintai.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.ui.ChartScreen
import com.footprintai.app.ui.ChartViewModel
import com.footprintai.app.ui.PortfolioScreen
import com.footprintai.app.ui.SettingsScreen
import com.footprintai.app.ui.SignalLogScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // 请求电池优化豁免（后台24/7运行）
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        startForegroundService(Intent(this, TradingService::class.java))

        setContent {
            // 强制深色模式：图表颜色基于深色背景设计
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainNavigation()
            }
        }
    }
}

@Composable
private fun MainNavigation() {
    val vm: ChartViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    icon     = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "Chart") },
                    label    = { Text("Chart") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    icon     = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Portfolio") },
                    label    = { Text("Portfolio") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick  = { selectedTab = 2 },
                    icon     = { Icon(Icons.Default.Notifications, contentDescription = "Signals") },
                    label    = { Text("Signals") },
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick  = { selectedTab = 3 },
                    icon     = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label    = { Text("Settings") },
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ChartScreen(vm = vm, modifier = Modifier.padding(padding))
            1 -> PortfolioScreen(vm = vm)
            2 -> SignalLogScreen(vm = vm)
            3 -> SettingsScreen(vm = vm)
        }
    }
}
