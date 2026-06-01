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
import java.io.File

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

@Composable
fun TunnelDashboard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sharedPrefs = remember { context.getSharedPreferences("cftunnel_prefs", Context.MODE_PRIVATE) }

    // 双屏切换导航状态：false = 主控制面板, true = 高级设置页面
    var isSettingPage by remember { mutableStateOf(false) }

    // 核心运行与配置状态
    var selectedTab by remember { mutableStateOf(0) }
    var portText by remember { mutableStateOf(sharedPrefs.getString("local_port", "8181") ?: "8181") }
    var tokenText by remember { mutableStateOf(sharedPrefs.getString("tunnel_token", "") ?: "") }

    var useFileServer by remember { mutableStateOf(sharedPrefs.getBoolean("use_file_server", true)) }
    var sharePath by remember { mutableStateOf(sharedPrefs.getString("share_path", "/storage/emulated/0/Download") ?: "/storage/emulated/0/Download") }
    var allowUpload by remember { mutableStateOf(sharedPrefs.getBoolean("allow_upload", false)) }
    
    var useAuth by remember { mutableStateOf(sharedPrefs.getBoolean("use_auth", false)) }
    var authPassword by remember { mutableStateOf(sharedPrefs.getString("auth_password", "") ?: "") }
    var protocolMode by remember { mutableStateOf(sharedPrefs.getInt("protocol_mode", 0)) }

    // 新增：详细调试日志模式状态持久化（默认关闭，只显示核心/警告日志）
    var isDebugMode by remember { mutableStateOf(sharedPrefs.getBoolean("is_debug_mode", false)) }

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

    // 智能日志自适应流式过滤器
    val filteredLogs = remember(logs, isDebugMode) {
        if (isDebugMode) {
            logs // 开启调试模式，输出 100% 全量原始日志
        } else {
            logs.filter { line ->
                // 1. 保留所有 APP、服务和文件服务器的原生中文日志
                if (line.contains("[UI]") || line.contains("[Service]") || 
                    line.contains("[System]") || line.contains("[FileServer]") || 
                    line.contains("[UI_Error]") || line.contains("[Service_Error]") || 
                    line.contains("[FileServer_Error]")) {
                    true
                } 
                // 2. 针对冗长的 Go 内核日志，仅精确拦截警告、报错、链接分配及握手成功标志
                else if (line.contains("[Kernel]")) {
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
            // 📲 1. 主控制面板界面 (Dashboard)
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
                
                // 设置齿轮按钮，点击滑入高级设置屏
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

            // 极简参数配置卡片
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

            // 核心运行/停止按钮
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
                            TunnelManager.startTunnel("系统拦截启动...")
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

            // 状态卡片（启动时自动显示丰富的参数配置回显）
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

            // 调试控制台日志（已应用智能过滤机制，点击标题右侧按钮一键秒复制、清空、折叠）
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
                                        // 复制机制安全升级：即使默认日志已被智能过滤精简，复制时依然全量复制 100% 原生日志，保障排查细节无损！
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
                                        logLine.contains("WRN") || logLine.contains("WARNING") -> Color(0xFFF59E0B)
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
                    // 文件服务器共享开关
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
                        
                        // 允许上传开关
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

                        // 密码验证开关
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

            // 协议选择设置（收纳进高级设置中，主面再无杂乱感）
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
                            text = "穿透传输协议",
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

            // 新增：详细调试模式开关，控制日志卡片默认输出量
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
