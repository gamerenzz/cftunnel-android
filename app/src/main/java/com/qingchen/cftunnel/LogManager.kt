package com.qingchen.cftunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        addLog("System", "日志管理器初始化成功")
    }

    fun addLog(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedLine = "[$time] [$tag] $message"
        
        // 同时输出到安卓系统底层的 Logcat，方便在 PC 联调时看
        android.util.Log.d("cftunnel_app", formattedLine)

        val current = _logs.value.toMutableList()
        if (current.size >= 150) { // 限制 150 行日志，防止内存溢出
            current.removeAt(0)
        }
        current.add(formattedLine)
        _logs.value = current
    }

    fun getAllLogsString(): String {
        return _logs.value.joinToString("\n")
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("System", "日志已由用户清空")
    }
}
