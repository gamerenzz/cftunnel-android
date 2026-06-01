package com.qingchen.cftunnel

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

object FileServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    var currentPath: String = ""

    fun start(port: Int, path: String): Boolean {
        return try {
            stop()
            currentPath = path
            serverSocket = ServerSocket(port)
            isRunning = true
            
            thread {
                LogManager.addLog("FileServer", "内嵌文件服务器开始监听底层网络端口: $port")
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        thread {
                            handleClient(client, path)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            LogManager.addLog("FileServer_Error", "接受客户端连接异常: ${e.message}")
                        }
                    }
                }
            }
            LogManager.addLog("FileServer", "内嵌网页文件共享服务器启动成功！物理路径: $path")
            true
        } catch (e: Exception) {
            LogManager.addLog("FileServer_Error", "内嵌文件服务器启动失败，原因: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            isRunning = false
            serverSocket?.close()
            serverSocket = null
            LogManager.addLog("FileServer", "内嵌网页共享服务器已被安全释放并关闭")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(socket: Socket, baseDir: String) {
        var reader: BufferedReader? = null
        var out: BufferedOutputStream? = null
        try {
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            out = BufferedOutputStream(socket.getOutputStream())

            // 1. 读取 HTTP 请求首行
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendTextResponse(out, 400, "Bad Request", "仅支持 HTTP GET 请求方法")
                return
            }

            // 2. 解码 URL 路径并过滤
            var reqPath = URLDecoder.decode(parts[1], "UTF-8")
            if (reqPath.contains("?")) {
                reqPath = reqPath.substringBefore("?")
            }

            // 核心修复点：强行去除开头的所有斜杠，并过滤掉 ".." 路径，防止 Java 判定其为绝对路径或进行目录穿越
            var cleanPath = reqPath.trimStart('/')
            if (cleanPath.contains("..")) {
                cleanPath = cleanPath.replace("..", "")
            }

            val targetFile = File(baseDir, cleanPath)
            if (!targetFile.exists()) {
                sendTextResponse(out, 404, "Not Found", "您请求的文件或文件夹不存在")
                return
            }

            if (targetFile.isDirectory) {
                // 3. 返回动态渲染的网页文件列表 HTML
                val html = buildDirectoryHtml(reqPath, targetFile)
                sendTextResponse(out, 200, "OK", html, "text/html; charset=utf-8")
            } else {
                // 4. 流式文件传输（下载）
                sendFileResponse(out, targetFile)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                out?.let { sendTextResponse(it, 500, "Internal Server Error", "内部错误: ${e.message}") }
            } catch (ex: Exception) { }
        } finally {
            try { reader?.close() } catch (e: Exception) {}
            try { out?.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun buildDirectoryHtml(relative: String, dir: File): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>cftunnel 共享文件系统</title>")
        sb.append("<style>")
        sb.append("body { font-family: -apple-system, sans-serif; background: #0f0f14; color: #e4e4ef; padding: 20px; margin: 0; }")
        sb.append("h2 { color: #60a5fa; font-size: 18px; border-bottom: 1.5px solid #2a2a3a; padding-bottom: 10px; word-break: break-all; }")
        sb.append("ul { list-style: none; padding: 0; margin: 0; }")
        sb.append("li { padding: 12px; border-bottom: 1.5px solid #1c1c28; display: flex; align-items: center; }")
        sb.append("a { color: #60a5fa; text-decoration: none; font-size: 15px; display: block; flex: 1; word-break: break-all; }")
        sb.append("a:hover { color: #3b82f6; }")
        sb.append(".back-btn { display: inline-block; padding: 6px 12px; background: #2a2a3a; border-radius: 6px; font-size: 13px; margin-bottom: 15px; color: #e4e4ef; }")
        sb.append("</style></head><body>")

        // 统一格式化显示路径
        val displayRelative = if (relative.isEmpty() || relative == "/") "/" else relative
        sb.append("<h2>📁 当前共享路径: $displayRelative</h2>")

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
                // 拼接子项链接时保持格式统一
                val link = "$relativeClean/${file.name}".replace("//", "/")
                sb.append("<li><a href='$link'>$icon${file.name}</a></li>")
            }
        }
        sb.append("</ul></body></html>")
        return sb.toString()
    }

    private fun sendTextResponse(out: BufferedOutputStream, code: Int, statusText: String, text: String, contentType: String = "text/plain; charset=utf-8") {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendFileResponse(out: BufferedOutputStream, file: File) {
        val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Content-Disposition: attachment; filename=\"$encodedName\"\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()

        val fis = FileInputStream(file)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            out.write(buffer, 0, bytesRead)
        }
        fis.close()
        out.flush()
    }
}
