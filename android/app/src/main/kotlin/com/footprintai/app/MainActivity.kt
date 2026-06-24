package com.footprintai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.ui.ChartScreen
import com.footprintai.app.ui.ChartViewModel
import com.footprintai.app.ui.PortfolioScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainNavigation()
            }
        }
    }
}

@Composable
private fun MainNavigation() {
    // 共享同一个 ViewModel — 两个 tab 共享状态和引擎
    val vm: ChartViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    icon     = { Icon(Icons.Default.ShowChart, contentDescription = "Chart") },
                    label    = { Text("Chart") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    icon     = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Portfolio") },
                    label    = { Text("Portfolio") },
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ChartScreen(vm = vm, modifier = Modifier.padding(padding))
            1 -> PortfolioScreen(vm = vm)
        }
    }
}
