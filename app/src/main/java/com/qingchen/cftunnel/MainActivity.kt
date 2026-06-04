package com.qingchen.cftunnel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                LogManager.addLog("System", "已确认拥有最高 [所有文件访问权限] 特权")
            }
        } else {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            val neededPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 100)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    LogManager.addLog("System", "检测到未加入 [电池优化白名单]，正在拉起系统申请窗口...")
                    Toast.makeText(this, "请将 cftunnel 设为 [无限制] 或允许后台高耗电运行，以防止息屏后网络穿透中断！", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                LogManager.addLog("System", "已成功加入系统 [电池优化白名单]")
            }
        }
    }
}

fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        val rawId = DocumentsContract.getTreeDocumentId(uri)
        val parts = rawId.split(":")
        if (parts.size >= 2) {
            val type = parts[0]
            val relativePath = parts[1]
            if ("primary".equals(type, ignoreCase = true)) {
                "/storage/emulated/0/$relativePath"
            } else {
                "/storage/$type/$relativePath"
            }
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// 异步 TCP 端口三次握手极速测速算法
suspend fun measureTcpPing(ip: String, port: Int = 443, timeoutMs: Int = 1000): Long {
    return withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val latency = System.currentTimeMillis() - start
            socket.close()
            latency
        } catch (e: Exception) {
            -1L // 代表连接超时或失败
        }
    }
}

@Composable
fun TunnelDashboard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sharedPrefs = remember { context.getSharedPreferences("cftunnel_prefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    var isSettingPage by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }
    var portText by remember { mutableStateOf(sharedPrefs.getString("local_port", "8181") ?: "8181") }
    var tokenText by remember { mutableStateOf(sharedPrefs.getString("tunnel_token", "") ?: "") }

    var useFileServer by remember { mutableStateOf(sharedPrefs.getBoolean("use_file_server", true)) }
    var sharePath by remember { mutableStateOf(sharedPrefs.getString("share_path", "/storage/emulated/0/Download") ?: "/storage/emulated/0/Download") }
    var allowUpload by remember { mutableStateOf(sharedPrefs.getBoolean("allow_upload", false)) }
    
    var useAuth by remember { mutableStateOf(sharedPrefs.getBoolean("use_auth", false)) }
    var authPassword by remember { mutableStateOf(sharedPrefs.getString("auth_password", "") ?: "") }
    var protocolMode by remember { mutableStateOf(sharedPrefs.getInt("protocol_mode", 0)) }
    var isDebugMode by remember { mutableStateOf(sharedPrefs.getBoolean("is_debug_mode", false)) }

    var usePreferredIp by remember { mutableStateOf(sharedPrefs.getBoolean("use_preferred_ip", false)) }
    var preferredIpMode by remember { mutableStateOf(sharedPrefs.getInt("preferred_ip_mode", 0)) }
    var customPreferredIp by remember { mutableStateOf(sharedPrefs.getString("custom_preferred_ip", "") ?: "") }

    // 新增：测速状态变量
    var isTestingSpeed by remember { mutableStateOf(false) }
    var speedTestResults by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

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
    LaunchedEffect(allowUpload) { sharedPrefs.edit().putBoolean("allow_upload", allowUpload).apply() }
    LaunchedEffect(useAuth) { sharedPrefs.edit().putBoolean("use_auth", useAuth).apply() }
    LaunchedEffect(authPassword) { sharedPrefs.edit().putString("auth_password", authPassword).apply() }
    LaunchedEffect(protocolMode) { sharedPrefs.edit().putInt("protocol_mode", protocolMode).apply() }
    LaunchedEffect(isDebugMode) { sharedPrefs.edit().putBoolean("is_debug_mode", isDebugMode).apply() }
    LaunchedEffect(usePreferredIp) { sharedPrefs.edit().putBoolean("use_preferred_ip", usePreferredIp).apply() }
    LaunchedEffect(preferredIpMode) { sharedPrefs.edit().putInt("preferred_ip_mode", preferredIpMode).apply() }
    LaunchedEffect(customPreferredIp) { sharedPrefs.edit().putString("custom_preferred_ip", customPreferredIp).apply() }

    val activePreferredIp = remember(usePreferredIp, preferredIpMode, customPreferredIp) {
        if (!usePreferredIp) ""
        else {
            when (preferredIpMode) {
                0 -> "104.16.123.96" 
                1 -> "104.18.2.85"  
                2 -> "104.20.123.96" 
                else -> customPreferredIp.trim() 
            }
        }
    }

    val filteredLogs = remember(logs, isDebugMode) {
        if (isDebugMode) {
            logs 
        } else {
            logs.filter { line ->
                if (line.contains("[UI]") || line.contains("[Service]") || 
                    line.contains("[System]") || line.contains("[FileServer]") || 
                    line.contains("[UI_Error]") || line.contains("[Service_Error]") || 
                    line.contains("[FileServer_Error]")) {
                    true
                } else if (line.contains("[Kernel]")) {
                    line.contains("ERR") || line.contains("WRN") || 
                    line.contains("WARNING") || line.contains("failed") || 
                    line.contains("Failed") || line.contains("trycloudflare.com") || 
                    line.contains("Registered tunnel connection") || 
                    line.contains("SUMMARY:") || line.contains("precheck") ||
                    line.contains("Tunnel server stopped")
                } else {
                    false
                }
            }
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            lazyListState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = getPathFromUri(context, uri)
            if (resolvedPath != null) {
                if (sharePath == "/storage/emulated/0/Download" || sharePath.isBlank()) {
                    sharePath = resolvedPath
                    LogManager.addLog("UI", "成功选择自定义共享目录: $resolvedPath")
                } else {
                    val currentList = sharePath.split(";").map { it.trim() }.toMutableList()
                    if (!currentList.contains(resolvedPath)) {
                        currentList.add(resolvedPath)
                        sharePath = currentList.joinToString(";")
                        LogManager.addLog("UI", "成功追加挂载共享目录: $resolvedPath")
                    } else {
                        Toast.makeText(context, "该目录已在共享列表中", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(context, "解析物理路径失败，请选择内部存储中的目录", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding()
            .verticalScroll(globalScrollState)
    ) {
        
        if (!isSettingPage) {
            // ==========================================
            // 📲 1. 主控制面板 (Dashboard)
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "cftunnel 控制台",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "⚙️ 高级设置",
                    color = Color(0xFF60A5FA),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { if(!isRunning) isSettingPage = true else Toast.makeText(context, "请先停止隧道后再修改设置", Toast.LENGTH_SHORT).show() }
                        .padding(8.dp)
                )
            }

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
                        serviceIntent.putExtra("allow_upload", allowUpload && selectedTab == 0)
                        serviceIntent.putExtra("use_auth", useAuth && selectedTab == 0)
                        serviceIntent.putExtra("auth_password", authPassword)
                        serviceIntent.putExtra("protocol_mode", protocolMode)
                        serviceIntent.putExtra("use_preferred_ip", usePreferredIp)
                        serviceIntent.putExtra("preferred_ip", activePreferredIp)

                        LogManager.addLog("UI", "用户点击了[启动隧道]按钮，端口: $portText，共享上传: $allowUpload，开启密码: $useAuth")

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
                            TunnelManager.updateStatus("启动受阻，错误: ${e.message}")
                            TunnelManager.stopTunnel()
                        }
                    } else {
                        LogManager.addLog("UI", "用户点击了[停止隧道]按钮")
                        try {
                            context.stopService(serviceIntent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF3B82F6)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isRunning) "停止隧道" else "启动隧道",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
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

                    if (isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "📁 已挂载共享路径:\n${sharePath.replace(";", "\n")}",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (useAuth && authPassword.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "🔐 公网访问密码:  $authPassword",
                                color = Color(0xFFF59E0B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "📡 隧道传输协议:  ${if(protocolMode == 0) "QUIC (高吞吐/UDP)" else "HTTP/2 (极稳/TCP)"}",
                            color = Color(0xFF22C55E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🚀 优选 IP 加速状态:  ${if(usePreferredIp && activePreferredIp.isNotEmpty()) "已启用 ($activePreferredIp)" else "未启用 (默认 DNS)"}",
                            color = if(usePreferredIp) Color(0xFF22C55E) else Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isRunning && selectedTab == 0 && generatedUrl.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
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
                            text = "📊 调试日志 (${filteredLogs.size}条)",
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
                                text = "复制全部",
                                color = Color(0xFF60A5FA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        val allLogs = LogManager.getAllLogsString()
                                        if (allLogs.isNotEmpty()) {
                                            clipboardManager.setText(AnnotatedString(allLogs))
                                            Toast.makeText(context, "所有底层调试日志已复制", Toast.LENGTH_SHORT).show()
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
                            items(filteredLogs) { logLine ->
                                val color = when {
                                    logLine.contains("[Kernel]") -> Color(0xFF34D399)
                                    logLine.contains("Error") || logLine.contains("失败") || logLine.contains("退出") || logLine.contains("ERR") || logLine.contains("FAIL") -> Color(0xFFF87171)
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
        } else {
            // ==========================================
            // ⚙️ 2. 高级设置界面 (Settings Page)
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "⬅️ 返回控制台",
                    color = Color(0xFF60A5FA),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { isSettingPage = false }
                        .padding(8.dp)
                )
            }

            Text(
                text = "高级配置项",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                            onCheckedChange = { useFileServer = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                        )
                    }

                    if (useFileServer) {
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "允许公网上传文件",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "外部用户可以通过生成的网页上传文件",
                                    color = Color(0xFF8888A0),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = allowUpload,
                                onCheckedChange = { allowUpload = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用公网访问密码",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "外部打开网页需要通过浏览器密码验证",
                                    color = Color(0xFF8888A0),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = useAuth,
                                onCheckedChange = { useAuth = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                            )
                        }

                        if (useAuth) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "设置访问密码",
                                color = Color(0xFFE4E4EF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = authPassword,
                                onValueChange = { authPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF2A2A3A),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                placeholder = { Text("请设置公网访问密码，如 1234", color = Color(0xFF8888A0)) },
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "共享文件夹路径 (多个路径以分号隔开)",
                            color = Color(0xFFE4E4EF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = sharePath,
                            onValueChange = { sharePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF2A2A3A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            placeholder = { Text("多个路径，如: /path/A;/path/B", color = Color(0xFF8888A0)) }
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
                                onClick = { sharePath = "/storage/emulated/0/Download" },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("默认下载目录", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("自定义目录 ➕", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = { sharePath = "/storage/emulated/0" },
                                modifier = btnModifier,
                                colors = btnColors,
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("整机存储", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 新增：Cloudflare 优选 IP 加速与高并发极速测速卡片（极简响应式设计） ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🚀 启用优选 IP 链路加速",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "将 Cloudflare 穿透连接强指国内极速节点",
                                color = Color(0xFF8888A0),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = usePreferredIp,
                            onCheckedChange = { usePreferredIp = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                        )
                    }

                    if (usePreferredIp) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // 多路高并发 TCP 端口测速按钮
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isTestingSpeed = true
                                    LogManager.addLog("UI", "用户触发了 [多路并发极速测速]...")
                                    
                                    // 预设高可靠三网优选 IP 测速池
                                    val ipPool = listOf(
                                        "104.16.123.96", // 移动/联通黄金推荐
                                        "104.18.2.85",  // 电信 163 推荐
                                        "104.20.123.96", // 三网通用 Anycast
                                        "104.16.124.96", // 移动备用
                                        "104.18.3.85",  // 电信备用
                                        "162.159.211.4" // 公共/CF 节点
                                    )

                                    // 使用协程并发 async 执行 443 端口三次握手测速
                                    val jobs = ipPool.map { ip ->
                                        async {
                                            val latency = measureTcpPing(ip)
                                            Pair(ip, latency)
                                        }
                                    }
                                    
                                    val results = jobs.awaitAll()
                                    // 过滤掉超时的 (-1)，按延迟升序排列
                                    val sortedResults = results.filter { it.second > 0 }.sortedBy { it.second }
                                    speedTestResults = sortedResults
                                    isTestingSpeed = false

                                    if (sortedResults.isNotEmpty()) {
                                        val bestIp = sortedResults[0].first
                                        customPreferredIp = bestIp
                                        preferredIpMode = 3 // 自动切换为“自定义手动输入”并锁定应用该 IP
                                        LogManager.addLog("UI", "⏱️ 极速测速成功！已自动锁定最优 Anycast 节点: $bestIp (${sortedResults[0].second}ms)")
                                        Toast.makeText(context, "测速成功！已自动应用最优节点: $bestIp (${sortedResults[0].second}ms)", Toast.LENGTH_LONG).show()
                                    } else {
                                        LogManager.addLog("UI_Error", "⏱️ 测速失败：所有节点均响应超时，请检查您的移动网络。")
                                        Toast.makeText(context, "所有优选节点均超时，请检查网络", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isTestingSpeed
                        ) {
                            Text(
                                text = if (isTestingSpeed) "⏱️ 正在全网高并发测速中..." else "⏱️ 开始多路并发极速测速",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        if (isTestingSpeed) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF3B82F6),
                                trackColor = Color(0xFF1F1F2E)
                            )
                        }

                        // 展示高档次测速结果列表
                        if (speedTestResults.isNotEmpty() && !isTestingSpeed) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "测速结果排行:",
                                color = Color(0xFF8888A0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                speedTestResults.take(3).forEachIndexed { index, pair ->
                                    val isBest = index == 0
                                    val tag = when (pair.first) {
                                        "104.16.123.96" -> "移动/联通 推荐"
                                        "104.18.2.85" -> "中国电信 推荐"
                                        "104.20.123.96" -> "三网通用 Anycast"
                                        else -> "备用节点"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F0F14), RoundedCornerShape(6.dp))
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(if (isBest) Color(0xFF22C55E) else Color(0xFFF59E0B), RoundedCornerShape(50))
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${pair.first} ($tag)",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Text(
                                            text = "${pair.second}ms ${if(isBest) "🟢 极速" else ""}",
                                            color = if (isBest) Color(0xFF22C55E) else Color(0xFF8888A0),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "选择优选档位",
                            color = Color(0xFFE4E4EF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preferredIpMode = 0 }
                                    .background(if (preferredIpMode == 0) Color(0xFF2A2A3A) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferredIpMode == 0, onClick = { preferredIpMode = 0 })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("移动 / 联通 推荐 (104.16.123.96 - 香港直连)", color = Color.White, fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preferredIpMode = 1 }
                                    .background(if (preferredIpMode == 1) Color(0xFF2A2A3A) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferredIpMode == 1, onClick = { preferredIpMode = 1 })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("中国电信 推荐 (104.18.2.85 - 骨干直连)", color = Color.White, fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preferredIpMode = 2 }
                                    .background(if (preferredIpMode == 2) Color(0xFF2A2A3A) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferredIpMode == 2, onClick = { preferredIpMode = 2 })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("三网均衡 智能 Anycast (104.20.123.96)", color = Color.White, fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preferredIpMode = 3 }
                                    .background(if (preferredIpMode == 3) Color(0xFF2A2A3A) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferredIpMode == 3, onClick = { preferredIpMode = 3 })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✍️ 自定义手动输入优选 IP", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        if (preferredIpMode == 3) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "当前锁定/应用优选 IP",
                                color = Color(0xFFE4E4EF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = customPreferredIp,
                                onValueChange = { customPreferredIp = it },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF2A2A3A),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                placeholder = { Text("请输入例如 104.16.124.96", color = Color(0xFF8888A0)) },
                                singleLine = true
                            )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "穿穿透传输协议",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "4G/5G数据掉线时，请切换至 HTTP/2",
                            color = Color(0xFF8888A0),
                            fontSize = 11.sp
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val quicColor = if (protocolMode == 0) Color(0xFF3B82F6) else Color(0xFF2A2A3A)
                        val h2Color = if (protocolMode == 1) Color(0xFF3B82F6) else Color(0xFF2A2A3A)

                        Button(
                            onClick = { protocolMode = 0 },
                            colors = ButtonDefaults.buttonColors(containerColor = quicColor),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("QUIC", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { protocolMode = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = h2Color),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("HTTP/2", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "内核调试详细日志 (Debug Mode)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "开启后日志栏将输出 100% 底层冗长日志",
                            color = Color(0xFF8888A0),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isDebugMode,
                        onCheckedChange = { isDebugMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6))
                    )
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
