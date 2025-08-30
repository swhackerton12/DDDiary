package com.woohyun.dddiary.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.woohyun.dddiary.feature.main.HomeScreen
import com.woohyun.dddiary.feature.settings.OptionScreen
import com.woohyun.dddiary.feature.stats.StatisticScreen
import com.woohyun.dddiary.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell() {
    val tabNav = rememberNavController()
    val backStack by tabNav.currentBackStackEntryAsState()
    val currentDest: NavDestination? = backStack?.destination
    val currentRoute = currentDest?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == Routes.TabStats,
                    onClick = {
                        tabNav.navigate(Routes.TabStats) {
                            popUpTo(tabNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                    label = { Text("통계") }
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.TabMain || currentRoute == null,
                    onClick = {
                        tabNav.navigate(Routes.TabMain) {
                            popUpTo(tabNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("홈") }
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.TabOptions,
                    onClick = {
                        tabNav.navigate(Routes.TabOptions) {
                            popUpTo(tabNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("설정") }
                )
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner)) {
            NavHost(tabNav, startDestination = Routes.TabMain) {
                composable(Routes.TabMain) { HomeScreen() }
                composable(Routes.TabStats) { StatisticScreen() }
                composable(Routes.TabOptions) { OptionScreen() }
            }
        }
    }
}
