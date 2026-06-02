package com.qingchen.cftunnel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

fun main() = singleWindowApplication(
    title = "cftunnel 控制台",
    state = WindowState(size = DpSize(400.dp, 650.dp)), // 强锁 400x650 黄金竖屏比例，完美匹配手机版 UI 设计
    resizable = false // 不允许用户拉伸变形，保持极简 Widget 像素级美感
) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F0F14) // 与手机版高度一致的深空灰底色
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Welcome to cftunnel Windows!\nCI/CD Pipeline Setup Successfully.",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
