package com.woohyun.dddiary

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.woohyun.dddiary.ui.theme.DDDiaryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DDDiaryTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Home {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(onOpenAccessibility: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ScrollWatch Demo", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onOpenAccessibility) { Text("접근성 설정 열기") }
        Text("켜고 나서 다른 앱에서 스크롤하면 Logcat(tag: ScrollWatch)에 기록됩니다.")
    }
}

@Preview
@Composable
private fun PreviewHome() {
    DDDiaryTheme { Home({}) }
}
