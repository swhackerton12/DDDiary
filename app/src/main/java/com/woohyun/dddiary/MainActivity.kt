package com.woohyun.dddiary

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import com.woohyun.dddiary.core.design.AppTheme
import com.woohyun.dddiary.navigation.AppNavHost
import com.woohyun.dddiary.navigation.Routes

class MainActivity : ComponentActivity() {

    private val requestPostNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPostNotification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val rootNav = rememberNavController()
            val start = Routes.Onboarding // DataStore 붙이면 MainShell로 분기

            AppTheme {
                LaunchedEffect(Unit) {
                    // 초기 훅 필요 시 사용
                }
                AppNavHost(nav = rootNav, start = start)
            }
        }
    }
}
