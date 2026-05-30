package com.qingchen.cftunnel

import android.app.*
import android.content.Intent
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
        createNotificationChannel()
        val notification = buildNotification("准备启动穿透内核...")
        startForeground(notificationId, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getIntExtra("mode", 0) ?: 0 // 0: 临时, 1: 永久
        val port = intent?.getStringExtra("port") ?: "8080"
        val token = intent?.getStringExtra("token") ?: ""

        stopTunnelProcess()
        TunnelManager.startTunnel()

        thread {
            val lastLogs = ArrayList<String>() // 用于收集最后几行日志
            try {
                val nativeDir = applicationInfo.nativeLibraryDir
                val fileSO = File(nativeDir, "libcloudflared.so")

                if (!fileSO.exists()) {
                    TunnelManager.updateStatus("错误: libcloudflared.so 动态库不存在")
                    return@thread
                }

                // 强制尝试赋予可执行权限，防止部分系统安全策略拦截
                try {
                    fileSO.setExecutable(true, false)
                } catch (e: Exception) {
                    lastLogs.add("授权执行失败: ${e.message}")
                }

                // 构建执行参数
                val command = if (mode == 0) {
                    listOf(fileSO.absolutePath, "tunnel", "--url", "http://127.0.0.1:$port")
                } else {
                    listOf(fileSO.absolutePath, "tunnel", "run", "--token", token)
                }

                val pb = ProcessBuilder(command)
                // 关键点：重定向环境变量，防止 Go 写入家目录失败导致闪退
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    val logLine = line ?: break
                    android.util.Log.d("cftunnel_kernel", logLine)

                    // 缓存最新的 5 行日志
                    if (lastLogs.size >= 5) {
                        lastLogs.removeAt(0)
                    }
                    lastLogs.add(logLine)

                    // 1. 临时隧道：抓取分配的公网 trycloudflare.com 域名
                    if (mode == 0 && logLine.contains("trycloudflare.com")) {
                        val regex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
                        val matchResult = regex.find(logLine)
                        if (matchResult != null) {
                            val realUrl = matchResult.value
                            TunnelManager.updateUrl(realUrl)
                            updateNotification("公网地址: $realUrl")
                        }
                    }
                    
                    // 2. 永久隧道：监听建立成功日志
                    if (mode == 1 && (logLine.contains("Connection established") || logLine.contains("Registered tunnel connection"))) {
                        TunnelManager.updateUrl("已成功连接至永久隧道")
                        updateNotification("永久隧道已成功建立连接")
                    }
                }

                // 读取结束，判断是否异常退出
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    val errorSummary = lastLogs.joinToString("\n")
                    TunnelManager.updateStatus("内核退出 ($exitCode)。控制台日志:\n$errorSummary")
                }

            } catch (e: Exception) {
                val errorSummary = lastLogs.joinToString("\n")
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopTunnelProcess()
        TunnelManager.stopTunnel()
        super.onDestroy()
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
