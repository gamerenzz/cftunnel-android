package com.qingchen.cftunnel

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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

        // 停止之前的进程
        stopTunnelProcess()

        TunnelManager.startTunnel()

        thread {
            try {
                val nativeDir = applicationInfo.nativeLibraryDir
                val cloudflaredPath = File(nativeDir, "libcloudflared.so").absolutePath

                // 构建执行参数
                val command = if (mode == 0) {
                    listOf(cloudflaredPath, "tunnel", "--url", "http://127.0.0.1:$port")
                } else {
                    listOf(cloudflaredPath, "tunnel", "run", "--token", token)
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
                    TunnelManager.updateStatus("内核异常退出，代码: $exitCode")
                }

            } catch (e: Exception) {
                TunnelManager.updateStatus("启动失败: ${e.message}")
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

    // --- 通知栏保活适配 ---
    private fun createNotificationChannel() {
        // 修正点：将等号(=)修改为了正常的点号(.)，表示 Android 8.0 Oreo 的代号
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
            .setSmallIcon(android.R.drawable.ic_menu_compass) // 使用系统内置指南针图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(text))
    }
}
