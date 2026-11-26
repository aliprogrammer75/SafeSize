package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.DashboardScreen
import com.example.ui.HomeScreen
import com.example.ui.ChartHomeScreen
import com.example.ui.DemoTradingScreen
import com.example.ui.TradingMenuScreen
import com.example.ui.MarketPlannerScreen
import com.example.ui.MarketWatchScreen
import com.example.ui.ExchangeHubScreen
import com.example.ui.RiskStudioScreen
import com.example.ui.ProductToolsScreen
import androidx.compose.material3.TextButton
import com.example.ui.BitunixTradingScreen
import com.example.ui.TradeAnalyticsScreen
import com.example.ui.TradeCalculatorScreen
import com.example.ui.TradeJournalScreen
import com.example.viewmodel.TradeViewModel
import com.example.ui.theme.AppTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        MainScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val viewModel: TradeViewModel = viewModel()

    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route
    fun go(destination: String) { navController.navigate(destination) { launchSingleTop = true } }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (route == "demo") "SafeSize • DEMO" else "SafeSize", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (route == "demo") "حساب تمرینی مستقل" else "مدیریت ریسک و معاملات", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (route != "dashboard") TextButton(onClick = { if (!navController.popBackStack()) go("dashboard") }) { Text("بازگشت") }
                    else TextButton(onClick = { go("exchange") }) { Text("حساب‌ها") }
                }
            }
        },
        bottomBar = {
            if (route != "demo") NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                val items = listOf(
                    Triple("dashboard", "داشبورد", Icons.Filled.Home),
                    Triple("chart", "چارت", Icons.Filled.Info),
                    Triple("calculator", "ماشین حساب", Icons.Filled.Add),
                    Triple("journal", "ژورنال", Icons.Filled.List),
                    Triple("exchange", "صرافی‌ها", Icons.Filled.Settings)
                )
                
                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") { HomeScreen(viewModel, onNavigate = { go(it) }) }
            composable("manual-dashboard") { DashboardScreen(viewModel) }
            composable("chart") { ChartHomeScreen() }
            composable("demo") { DemoTradingScreen() }
            composable("trade-menu") { TradingMenuScreen(onNavigate = { go(it) }) }
            composable("calculator") { MarketPlannerScreen(onManual = { navController.navigate("manual-calculator") }, onWatch = { navController.navigate("watch") }) }
            composable("manual-calculator") { TradeCalculatorScreen(viewModel) }
            composable("watch") { MarketWatchScreen() }
            composable("journal") { TradeJournalScreen(viewModel) }
            composable("analytics") { TradeAnalyticsScreen(viewModel) }
            composable("exchange") { ExchangeHubScreen(onTrade = { navController.navigate("bitunix-trade") }) }
            composable("bitunix-trade") { BitunixTradingScreen() }
            composable("risk") { RiskStudioScreen(viewModel) }
            composable("tools") { ProductToolsScreen(viewModel, onRisk = { navController.navigate("risk") }) }
        }
    }
}
