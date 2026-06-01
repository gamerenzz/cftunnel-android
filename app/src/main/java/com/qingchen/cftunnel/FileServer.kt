package com.qingchen.cftunnel

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder

object FileServer {
    private var server: HttpServer? = null
    var currentPath: String = ""

    fun start(port: Int, path: String): Boolean {
        return try {
            stop()
            currentPath = path
            server = HttpServer.create(InetSocketAddress(port), 0)
            server?.createContext("/", FileHandler(path))
            server?.executor = null
            server?.start()
            LogManager.addLog("FileServer", "内嵌文件服务器启动成功！端口: $port, 共享目录: $path")
            true
        } catch (e: Exception) {
            LogManager.addLog("FileServer_Error", "启动失败，原因: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            server = null
            LogManager.addLog("FileServer", "内嵌文件服务器已安全停止并释放端口")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class FileHandler(val baseDir: String) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                // 解码 URL 路径（防止中文文件名乱码）
                val requestedPath = URLDecoder.decode(exchange.requestURI.path, "UTF-8")
                val targetFile = File(baseDir, requestedPath)

                if (!targetFile.exists()) {
                    sendResponse(exchange, 404, "文件或目录不存在")
                    return
                }

                if (targetFile.isDirectory) {
                    // 1. 如果请求的是文件夹，动态生成精美的 HTML 页面列出文件
                    val html = buildDirectoryHtml(requestedPath, targetFile)
                    sendResponse(exchange, 200, html, "text/html; charset=utf-8")
                } else {
                    // 2. 如果请求的是文件，以流式传输触发外部浏览器原生下载
                    sendFile(exchange, targetFile)
                }
            } catch (e: Exception) {
                sendResponse(exchange, 500, "服务器内部错误: ${e.message}")
            }
        }

        private fun buildDirectoryHtml(relative: String, dir: File): String {
            val sb = StringBuilder()
            sb.append("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>")
            sb.append("<title>cftunnel 文件共享列表</title>")
            sb.append("<style>")
            sb.append("body { font-family: -apple-system, sans-serif; background: #0f0f14; color: #e4e4ef; padding: 20px; margin: 0; }")
            sb.append("h2 { color: #60a5fa; font-size: 18px; border-bottom: 1.5px solid #2a2a3a; padding-bottom: 10px; word-break: break-all; }")
            sb.append("ul { list-style: none; padding: 0; margin: 0; }")
            sb.append("li { padding: 12px; border-bottom: 1.5px solid #1c1c28; display: flex; align-items: center; }")
            sb.append("a { color: #60a5fa; text-decoration: none; font-size: 15px; display: block; flex: 1; word-break: break-all; }")
            sb.append("a:hover { color: #3b82f6; }")
            sb.append(".back-btn { display: inline-block; padding: 6px 12px; background: #2a2a3a; border-radius: 6px; font-size: 13px; margin-bottom: 15px; color: #e4e4ef; }")
            sb.append("</style></head><body>")
            
            sb.append("<h2>📁 当前共享路径: ${if (relative.isEmpty() || relative == "/") "/" else relative}</h2>")

            val relativeClean = if (relative == "/") "" else relative
            if (relativeClean.isNotEmpty()) {
                val parent = relativeClean.substringBeforeLast("/")
                val parentLink = if (parent.isEmpty()) "/" else parent
                sb.append("<a class='back-btn' href='$parentLink'>⬅️ 返回上级目录</a>")
            }

            sb.append("<ul>")
            val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            if (files.isNullOrEmpty()) {
                sb.append("<li><span style='color: #8888a0;'>空文件夹</span></li>")
            } else {
                files.forEach { file ->
                    val icon = if (file.isDirectory) "📁 " else "📄 "
                    val link = "$relativeClean/${file.name}".replace("//", "/")
                    sb.append("<li><a href='$link'>$icon${file.name}</a></li>")
                }
            }
            sb.append("</ul></body></html>")
            return sb.toString()
        }

        private fun sendResponse(exchange: HttpExchange, code: Int, text: String, contentType: String = "text/plain; charset=utf-8") {
            val bytes = text.toByteArray()
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            val os = exchange.responseBody
            os.write(bytes)
            os.close()
        }

        private fun sendFile(exchange: HttpExchange, file: File) {
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            // 设定 Header 触发浏览器弹出下载窗口
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"${java.net.URLEncoder.encode(file.name, "UTF-8")}\"")
            exchange.sendResponseHeaders(200, file.length())

            val fis = FileInputStream(file)
            val os: OutputStream = exchange.responseBody
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                os.write(buffer, 0, bytesRead)
            }
            fis.close()
            os.close()
        }
    }
}
