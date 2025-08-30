package com.woohyun.dddiary.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object OB {
    const val Init = "ob_init"
    const val UserInfo = "ob_user"
    const val Confirm = "ob_confirm"
}

@Composable
fun OnboardingNav(onFinish: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = OB.Init) {
        composable(OB.Init) { InitScreen(onNext = { nav.navigate(OB.UserInfo) }) }
        composable(OB.UserInfo) {
            UserInfoScreen(
                onNext = { nav.navigate(OB.Confirm) },
                onBack = { nav.popBackStack() }
            )
        }
        composable(OB.Confirm) {
            ConfirmScreen(
                onDone = onFinish,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
