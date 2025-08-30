package com.woohyun.dddiary.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UserInfoScreen(onNext: () -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var ageGroup by remember { mutableStateOf("20대") }
    var target by remember { mutableStateOf("60") }

    OnboardingScaffold(title = "사용자 정보", onBack = onBack) {
        Column(Modifier.padding(20.dp)) {
            Text("기본 정보")
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("닉네임") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ageGroup, onValueChange = { ageGroup = it },
                label = { Text("연령대") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = target, onValueChange = { target = it },
                label = { Text("하루 목표(분)") }, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) { Text("이전") }
                Button(onClick = onNext) { Text("완료") }
            }
        }
    }
}
