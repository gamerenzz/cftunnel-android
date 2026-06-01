package com.qingchen.cftunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F14)
                ) {
                    TunnelDashboard()
                }
            }
        }
    }
}

@Composable
fun TunnelDashboard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sharedPrefs = remember { context.getSharedPreferences("cftunnel_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf(0) }
    var portText by remember { mutableStateOf(sharedPrefs.getString("local_port", "8080") ?: "8080") }
    var tokenText by remember { mutableStateOf(sharedPrefs.getString("tunnel_token", "") ?: "") }

    // 状态观察
    val isRunning by TunnelManager.isRunning.collectAsState()
    val generatedUrl by TunnelManager.tunnelUrl.collectAsState()
    val statusText by TunnelManager.statusText.collectAsState()

    // 日志与调试折叠状态
    val logs by LogManager.logs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(portText) { sharedPrefs.edit().putString("local_port", portText).apply() }
    LaunchedEffect(tokenText) { sharedPrefs.edit().putString("tunnel_token", tokenText).apply() }

    // 当有新日志产生时，自动滚动到最底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            lazyListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding()
    ) {
        Text(
            text = "cftunnel 控制台",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF161622),
            contentColor = Color(0xFF3B82F6),
            modifier = Modifier.background(Color(0xFF161622), RoundedCornerShape(8.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { if (!isRunning) selectedTab = 0 },
                text = { Text("免域名模式", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                selectedContentColor = Color(0xFF60A5FA),
                unselectedContentColor = Color(0xFF8888A0)
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { if (!isRunning) selectedTab = 1 },
                text = { Text("永久隧道", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                selectedContentColor = Color(0xFF60A5FA),
                unselectedContentColor = Color(0xFF8888A0)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (selectedTab == 0) {
                    Text(
                        text = "配置本地转发端口",
                        color = Color(0xFFE4E4EF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = portText,
                        onValueChange = { if (!isRunning) portText = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF2A2A3A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        placeholder = { Text("请输入端口，如 8080", color = Color(0xFF8888A0)) },
                        singleLine = true,
                        enabled = !isRunning
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val portVal = portText.toIntOrNull()
                    if (portVal != null && portVal < 1024) {
                        Text(
                            text = "⚠️ 注意：当前端口小于 1024。在非 Root 安卓设备上，系统可能限制使用低于 1024 的端口。若启动失败，请将您的本地服务端口调整至 1024 以上（例如 8080）。",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else if (portVal != null) {
                        Text(
                            text = "✓ 该端口范围（>= 1024）适合非 Root 设备正常运行。",
                            color = Color(0xFF22C55E),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Text(
                        text = "配置 Cloudflare 隧道 Token",
                        color = Color(0xFFE4E4EF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { if (!isRunning) tokenText = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF2A2A3A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        placeholder = { Text("请输入以 eyJ... 开头的运行 Token", color = Color(0xFF8888A0)) },
                        enabled = !isRunning
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val serviceIntent = Intent(context, TunnelService::class.java)
                    if (!isRunning) {
                        if (selectedTab == 1 && tokenText.isBlank()) {
                            Toast.makeText(context, "请先输入 Token", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        serviceIntent.putExtra("mode", selectedTab)
                        serviceIntent.putExtra("port", portText)
                        serviceIntent.putExtra("token", tokenText)

                        LogManager.addLog("UI", "用户点击了[启动隧道]按钮，端口: $portText")

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            LogManager.addLog("UI_Error", "启动服务底层抛出异常: ${e.message}")
                            Toast.makeText(context, "启动服务受阻: ${e.message}", Toast.LENGTH_LONG).show()
                            TunnelManager.startTunnel("荣耀系统拦截了后台启动...")
                            TunnelManager.updateStatus(
                                "⚠️ 启动受阻！您的荣耀手机可能默认拦截了本应用的后台保活与自启动机制。\n" +
                                "请尝试前往：【系统设置 -> 应用 -> 应用启动管理 -> 找到 cftunnel -> 关闭自动管理 -> 开启允许自启动、允许后台运行】后再试。\n" +
                                "系统底层错误: ${e.message}"
                            )
                            TunnelManager.stopTunnel()
                        }
                    } else {
                        LogManager.addLog("UI", "用户点击了[停止隧道]按钮")
                        try {
                            context.stopService(serviceIntent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            LogManager.addLog("UI_Error", "停止服务发生异常: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF3B82F6)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isRunning) "停止隧道" else "启动隧道",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "运行状态",
                    color = Color(0xFFE4E4EF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
                                shape = RoundedCornerShape(50)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                if (isRunning && selectedTab == 0 && generatedUrl.isNotEmpty()) {
                    Text(
                        text = "公网临时访问地址 (已联调真实内核):",
                        color = Color(0xFF8888A0),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F0F14), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = generatedUrl,
                            color = Color(0xFF60A5FA),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(generatedUrl))
                            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("复制链接", color = Color(0xFF60A5FA))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 核心调试日志卡片（新增，支持展开折叠、长按选择与一键复制全部） ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogs = !showLogs },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "📊 调试控制台日志 (${logs.size}条)",
                        color = Color(0xFFE4E4EF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (showLogs) "▲ 收起日志" else "▼ 展开日志",
                        color = Color(0xFF60A5FA),
                        fontSize = 12.sp
                    )
                }

                if (showLogs) {
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // 日志输出框（SelectionContainer 允许用户用安卓系统原生选择条选中任何日志行）
                    SelectionContainer {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color(0xFF0F0F14), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF2A2A3A), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            items(logs) { logLine ->
                                val color = when {
                                    logLine.contains("[Kernel]") -> Color(0xFF34D399) // 绿色：内核正常日志
                                    logLine.contains("Error") || logLine.contains("失败") || logLine.contains("退出") -> Color(0xFFF87171) // 红色：警告/错误
                                    else -> Color(0xFF9CA3AF) // 灰色：系统基础流
                                }
                                Text(
                                    text = logLine,
                                    color = color,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val allLogs = LogManager.getAllLogsString()
                                if (allLogs.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(allLogs))
                                    Toast.makeText(context, "所有调试日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("复制全部日志", color = Color.White, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { LogManager.clearLogs() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("清空控制台", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A26)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "💡 温馨提示",
                    color = Color(0xFFF59E0B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "1. 网页 (HTTP/WS) 穿透后，外部直接使用浏览器即可访问，无需在访问端安装任何工具。\n" +
                            "2. TCP / UDP / SSH 穿透后，外部访问端必须同步运行 cloudflared 客户端建立本地端口映射，无法直接通过浏览器访问。",
                    color = Color(0xFF8888A0),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
