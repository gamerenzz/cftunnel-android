package com.qingchen.cftunnel

import android.content.Context
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object CFEdgeSpeedTest {

    // 预置的 Cloudflare 黄金 Anycast 优选 IP 数据库
    val edgeIPs = listOf(
        "104.16.123.96",
        "104.18.2.85",
        "104.20.123.96",
        "104.16.124.96",
        "104.18.3.85"
    )

    private const val TCP_PORT = 7844
    private const val TIMEOUT_MS = 1000 // 1秒超时，保障测速极其高效不卡顿

    // 单个 IP 的测试结果数据结构
    data class TestResult(val ip: String, val rtt: Long?)

    /**
     * 并行测速核心：利用 Kotlin 协程对所有候选 IP 的 7844 物理端口同步发起 TCP 握手
     */
    suspend fun runSpeedTest(onProgress: (List<TestResult>) -> Unit): String? = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Long>()
        val uiList = edgeIPs.map { TestResult(it, null) }.toMutableList()

        coroutineScope {
            edgeIPs.mapIndexed { index, ip ->
                async {
                    val rtt = pingTCP(ip, TCP_PORT)
                    if (rtt != null) {
                        results[ip] = rtt
                    }
                    synchronized(uiList) {
                        uiList[index] = TestResult(ip, rtt)
                        // 实时回调当前的测速状态（进度条）
                        onProgress(uiList.toList())
                    }
                }
            }.awaitAll()
        }

        // 选出 RTT 最低（延迟最小）的黄金优选 IP
        val bestIP = results.minByOrNull { it.value }?.key
        LogManager.addLog("SpeedTest", "全网极速测速完毕。本局最佳优选 IP 为: $bestIP (${results[bestIP]}ms)")
        return@withContext bestIP
    }

    private fun pingTCP(host: String, port: Int): Long? {
        return try {
            val ms = measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                }
            }
            ms
        } catch (e: Exception) {
            null
        }
    }
}
