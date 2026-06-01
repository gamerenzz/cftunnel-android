package com.qingchen.cftunnel

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayList
import kotlin.concurrent.thread

class TunnelService : Service() {

    private var process: Process? = null
    private val notificationId = 1024
    private val channelId = "cftunnel_service_channel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LogManager.addLog("Service", "onCreate()：前台保活服务正在被安卓系统初始化...")
        createNotificationChannel()
        val notification = buildNotification("准备启动穿透内核...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
        LogManager.addLog("Service", "startForeground()：前台保活服务已挂载到状态栏")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getIntExtra("mode", 0) ?: 0
        val port = intent?.getStringExtra("port") ?: "8080"
        val token = intent?.getStringExtra("token") ?: ""
        
        val useFileServer = intent?.getBooleanExtra("use_file_server", false) ?: false
        val sharePath = intent?.getStringExtra("share_path") ?: ""

        stopTunnelProcess()
        TunnelManager.startTunnel()

        if (mode == 0 && useFileServer && sharePath.isNotEmpty()) {
            val success = FileServer.start(port.toInt(), sharePath)
            if (!success) {
                LogManager.addLog("Service_Error", "内嵌文件服务器启动失败，请检查端口是否被占用")
                TunnelManager.updateStatus("错误: 文件服务器启动失败")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        thread {
            val lastLogs = ArrayList<String>()
            try {
                val nativeDir = applicationInfo.nativeLibraryDir
                val fileSO = File(nativeDir, "libcloudflared.so")

                if (!fileSO.exists()) {
                    LogManager.addLog("Service_Error", "libcloudflared.so 动态库在本地 nativeLibraryDir 目录中未找到")
                    TunnelManager.updateStatus("错误: 内核动态库不存在于系统目录")
                    return@thread
                }

                try {
                    LogManager.addLog("Service", "正在尝试对底层内核授予可执行权限 (r-xr-xr-x)...")
                    fileSO.setExecutable(true, false)
                    LogManager.addLog("Service", "授权完成，权限状态：rwx=${fileSO.canExecute()}")
                } catch (e: Exception) {
                    LogManager.addLog("Service_Error", "授权失败: ${e.message}")
                    lastLogs.add("授权执行失败: ${e.message}")
                }

                val command = if (mode == 0) {
                    listOf(fileSO.absolutePath, "tunnel", "--url", "http://127.0.0.1:$port")
                } else {
                    listOf(fileSO.absolutePath, "tunnel", "run", "--token", token)
                }

                LogManager.addLog("Service", "构建执行进程指令: ${command.joinToString(" ")}")

                val pb = ProcessBuilder(command)
                // 关键点 1：重定向环境变量，防止 Go 写入家目录失败导致闪退
                pb.environment()["HOME"] = filesDir.absolutePath
                // 关键点 2（新增）：强行锁死运行期 Go 只准使用安卓原生 CGO DNS 解析，彻底杜绝其退回 [::1]:53 导致闪退
                pb.environment()["GODEBUG"] = "netdns=cgo"
                
                pb.redirectErrorStream(true)

                LogManager.addLog("Service", "正在拉起 ProcessBuilder 执行底层二进制进程...")
                val proc = pb.start()
                process = proc
                LogManager.addLog("Service", "成功拉起二进制内核进程，PID = ${getProcessId(proc)}")

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    val logLine = line ?: break
                    LogManager.addLog("Kernel", logLine)

                    if (lastLogs.size >= 5) {
                        lastLogs.removeAt(0)
                    }
                    lastLogs.add(logLine)

                    if (mode == 0 && logLine.contains("trycloudflare.com")) {
                        val regex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
                        val matchResult = regex.find(logLine)
                        if (matchResult != null) {
                            val realUrl = matchResult.value
                            TunnelManager.updateUrl(realUrl)
                            updateNotification("公网地址: $realUrl")
                        }
                    }
                    
                    if (mode == 1 && (logLine.contains("Connection established") || logLine.contains("Registered tunnel connection"))) {
                        TunnelManager.updateUrl("已成功连接至永久隧道")
                        updateNotification("永久隧道已成功建立连接")
                    }
                }

                val exitCode = proc.waitFor()
                LogManager.addLog("Service", "内核进程执行结束，退出码（Exit Code）: $exitCode")
                if (exitCode != 0) {
                    val errorSummary = lastLogs.joinToString("\n")
                    TunnelManager.updateStatus("内核退出 ($exitCode)。最新日志:\n$errorSummary")
                }

            } catch (e: Exception) {
                val errorSummary = lastLogs.joinToString("\n")
                LogManager.addLog("Service_Error", "内核运行过程中抛出未捕获异常:\n${e.message}")
                TunnelManager.updateStatus("启动发生异常:\n${e.message}\n$errorSummary")
            } finally {
                TunnelManager.stopTunnel()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun stopTunnelProcess() {
        try {
            process?.destroy()
            process = null
            LogManager.addLog("Service", "已向内核进程发送销毁信号 (SIGKILL/destroy)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        LogManager.addLog("Service", "onDestroy()：后台保活服务生命周期结束，正在执行资源回收...")
        stopTunnelProcess()
        FileServer.stop()
        TunnelManager.stopTunnel()
        super.onDestroy()
    }

    private fun getProcessId(p: Process): String {
        return try {
            p.toString().substringAfter("pid=").substringBefore(",")
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "cftunnel 运行状态监听",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("cftunnel 正在后台保障穿透")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(text))
    }
}
