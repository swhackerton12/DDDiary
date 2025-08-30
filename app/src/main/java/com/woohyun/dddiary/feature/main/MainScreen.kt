package com.woohyun.dddiary.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MainScreen() {
    // TopAppBar 제목은 MainShell에서 표시하므로
    // 여기서는 본문만 보여준다.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("도파민 Lv. 60 (placeholder)")
    }
}
