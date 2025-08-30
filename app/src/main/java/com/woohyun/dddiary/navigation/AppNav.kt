package com.woohyun.dddiary.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.woohyun.dddiary.feature.onboarding.OnboardingNav
import com.woohyun.dddiary.shell.MainShell

object Routes {
    // 루트
    const val Onboarding = "onboarding"
    const val MainShell = "main_shell"

    // 탭 내부 라우트
    const val TabMain = "tab_main"
    const val TabStats = "tab_stats"
    const val TabOptions = "tab_options"
}

@Composable
fun AppNavHost(nav: NavHostController, start: String) {
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.Onboarding) {
            OnboardingNav(
                onFinish = {
                    nav.navigate(Routes.MainShell) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                }
            )
        }
        // 탭을 담는 쉘(TopAppBar + BottomBar + 내부 NavHost)
        composable(Routes.MainShell) { MainShell() }
    }
}
