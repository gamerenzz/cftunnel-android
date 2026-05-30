package com.qingchen.cftunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TunnelManager {
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _tunnelUrl = MutableStateFlow("")
    val tunnelUrl = _tunnelUrl.asStateFlow()

    private val _statusText = MutableStateFlow("隧道未启动")
    val statusText = _statusText.asStateFlow()

    fun startTunnel(initialStatus: String = "正在启动守护进程...") {
        _isRunning.value = true
        _statusText.value = initialStatus
        _tunnelUrl.value = ""
    }

    fun updateUrl(url: String) {
        _tunnelUrl.value = url
        _statusText.value = "隧道运行中 (已成功映射)"
    }

    fun updateStatus(status: String) {
        _statusText.value = status
    }

    fun stopTunnel() {
        _isRunning.value = false
        _tunnelUrl.value = ""
        _statusText.value = "隧道已停止"
    }
}
