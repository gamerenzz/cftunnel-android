package com.qingchen.cftunnel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启动时强制执行最高文件管理特权检测与系统引导
        checkAndRequestPermissions()

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

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            // 检测是否已经获得了所有文件访问特权
            if (!Environment.isExternalStorageManager()) {
                try {
                    LogManager.addLog("System", "检测到未获得 [所有文件访问权限]，正在引导跳转至系统设置页...")
                    Toast.makeText(this, "请授予 cftunnel [所有文件访问权限] 以读取您指定的共享文件夹", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        addCategory("android.intent.category.DEFAULT")
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // 备用方案：如果部分定制系统无法直接精确跳转到本应用设置页，则跳转至全局管理页
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                LogManager.addLog("System", "已确认拥有最高 [所有文件访问权限] 特权")
            }
        } else {
            // Android 11 以下系统的常规动态权限申请
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            val neededPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 100)
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
    var portText by remember { mutableStateOf(sharedPrefs.getString("local_port", "8181") ?: "8181") }
    var tokenText by remember { mutableStateOf(sharedPrefs.getString("tunnel_token", "") ?: "") }

    var useFileServer by remember { mutableStateOf(sharedPrefs.getBoolean("use_file_server", true)) }
    var sharePath by remember { mutableStateOf(sharedPrefs.getString("share_path", "/storage/emulated/0/Download") ?: "/storage/emulated/0/Download") }

    val isRunning by TunnelManager.isRunning.collectAsState()
    val generatedUrl by TunnelManager.tunnelUrl.collectAsState()
    val statusText by TunnelManager.statusText.collectAsState()
    
    val logs by LogManager.logs.collectAsState()
    var showLogs by remember { mutableStateOf(true) } 
    val lazyListState = rememberLazyListState()
    val globalScrollState = rememberScrollState()

    LaunchedEffect(portText) { sharedPrefs.edit().putString("local_port", portText).apply() }
    LaunchedEffect(tokenText) { sharedPrefs.edit().putString("tunnel_token", tokenText).apply() }
    LaunchedEffect(useFileServer) { sharedPrefs.edit().putBoolean("use_file_server", useFileServer).apply() }
    LaunchedEffect(sharePath) { sharedPrefs.edit().putString("share_path", sharePath).apply() }

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
            .verticalScroll(globalScrollState)
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
                        placeholder = { Text("请输入端口，如 8181", color = Color(0xFF8888A0)) },
                        singleLine = true,
                        enabled = !isRunning
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "内嵌网页文件共享",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "一键将指定文件夹分享到公网",
                                color = Color(0xFF8888A0),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = useFileServer,
                            onCheckedChange = { if (!isRunning) useFileServer = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                        )
                    }

                    if (useFileServer) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "共享文件夹路径",
                            color = Color(0xFFE4E4EF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = sharePath,
                            onValueChange = { if (!isRunning) sharePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF2A2A3A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            placeholder = { Text("请指定共享路径，例如 /storage/emulated/0", color = Color(0xFF8888A0)) },
                            singleLine = true,
                            enabled = !isRunning
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val btnModifier = Modifier.weight(1f)
                            val btnColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A))
                            val btnShape = RoundedCornerShape(6.dp)

                            Button(
                                onClick = { if (!isRunning) sharePath = "/storage/emulated/0/Download" },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("填入下载目录", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = { if (!isRunning) sharePath = "/storage/emulated/0/Documents" },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("填入文档目录", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = { if (!isRunning) sharePath = "/storage/emulated/0" },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("填入整机存储", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }

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
                        serviceIntent.putExtra("use_file_server", useFileServer && selectedTab == 0)
                        serviceIntent.putExtra("share_path", sharePath)

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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "📊 调试日志 (${logs.size}条)",
                        color = Color(0xFFE4E4EF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showLogs = !showLogs }
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "复制",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val allLogs = LogManager.getAllLogsString()
                                    if (allLogs.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(allLogs))
                                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "清空",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { LogManager.clearLogs() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Text(
                            text = if (showLogs) "▲ 收起" else "▼ 展开",
                            color = Color(0xFF8888A0),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { showLogs = !showLogs }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (showLogs) {
                    Spacer(modifier = Modifier.height(10.dp))
                    
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
                                    logLine.contains("[Kernel]") -> Color(0xFF34D399)
                                    logLine.contains("Error") || logLine.contains("失败") || logLine.contains("退出") || logLine.contains("ERR") -> Color(0xFFF87171)
                                    else -> Color(0xFF9CA3AF)
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
