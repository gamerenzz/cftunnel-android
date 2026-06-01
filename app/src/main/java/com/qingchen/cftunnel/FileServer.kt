package com.qingchen.cftunnel

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.ArrayList
import kotlin.concurrent.thread

object FileServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    var currentPath: String = ""
    var isUploadAllowed: Boolean = false
    
    // 新增：全局密码控制字段
    var isAuthEnabled: Boolean = false
    var authPasswordStr: String = ""

    fun start(port: Int, path: String, allowUpload: Boolean, useAuth: Boolean, authPass: String): Boolean {
        return try {
            stop()
            currentPath = path
            isUploadAllowed = allowUpload
            isAuthEnabled = useAuth
            authPasswordStr = authPass
            
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
            LogManager.addLog("FileServer", "内嵌网页文件服务器启动成功！允许公网上传: $allowUpload, 启用密码保护: $useAuth")
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

    private fun readHeaders(inputStream: InputStream): Pair<List<String>, Int> {
        val headers = ArrayList<String>()
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        var b: Int
        var byteCount = 0
        
        while (inputStream.read().also { b = it } != -1) {
            byteCount++
            headerBytes.write(b)
            
            if (b == '\r'.toInt() && state == 0) state = 1
            else if (b == '\n'.toInt() && state == 1) state = 2
            else if (b == '\r'.toInt() && state == 2) state = 3
            else if (b == '\n'.toInt() && state == 3) {
                break
            } else {
                state = 0
            }
        }
        
        val headerText = headerBytes.toString("UTF-8")
        val lines = headerText.split("\r\n")
        return Pair(lines, byteCount)
    }

    private fun handleClient(socket: Socket, rawPaths: String) {
        var rawIn: InputStream? = null
        var out: BufferedOutputStream? = null
        try {
            rawIn = socket.getInputStream()
            out = BufferedOutputStream(socket.getOutputStream())

            val (headers, _) = readHeaders(rawIn)
            if (headers.isEmpty() || headers[0].isEmpty()) return

            val reqLine = headers[0]
            val parts = reqLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            var reqPath = URLDecoder.decode(parts[1], "UTF-8")
            if (reqPath.contains("?")) {
                reqPath = reqPath.substringBefore("?")
            }

            // --- 终极注入点 1：公网访问安全密码校验 (HTTP Basic Auth) ---
            if (isAuthEnabled && authPasswordStr.isNotEmpty()) {
                var isAuthorized = false
                var authHeader: String? = null
                
                // 从请求头中检索 Authorization 授权头
                for (h in headers) {
                    if (h.lowercase().startsWith("authorization:")) {
                        authHeader = h.substringAfter(":").trim()
                    }
                }
                
                if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
                    val base64Credentials = authHeader.substring(6).trim()
                    try {
                        // 使用安卓内置的 Base64 库进行解码
                        val decodedBytes = android.util.Base64.decode(base64Credentials, android.util.Base64.DEFAULT)
                        val credentials = String(decodedBytes, Charsets.UTF_8)
                        // HTTP 规范标准格式为 "用户名:密码"，此处提取出密码进行比对
                        val credentialParts = credentials.split(":")
                        if (credentialParts.size >= 2) {
                            val clientPassword = credentialParts[1]
                            if (clientPassword == authPasswordStr) {
                                isAuthorized = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // 如果未授权，向浏览器发送 401 挑战信息，强行弹出原生登录弹窗
                if (!isAuthorized) {
                    LogManager.addLog("FileServer_Warning", "拒绝匿名公网访问：触发密码保护机制，已发送认证挑战。")
                    val challengeHeader = "HTTP/1.1 401 Unauthorized\r\n" +
                            "WWW-Authenticate: Basic realm=\"cftunnel Private File Sharing\"\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: 32\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                    out.write(challengeHeader.toByteArray(Charsets.UTF_8))
                    out.write("401 Unauthorized: Access Denied.".toByteArray(Charsets.UTF_8))
                    out.flush()
                    return // 直接拦截阻断，后续逻辑绝不执行
                }
            }

            // 解析多个物理路径列表
            val sharedPaths = rawPaths.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            if (sharedPaths.isEmpty()) {
                sendTextResponse(out, 500, "Internal Error", "未配置有效的本地共享目录")
                return
            }

            val isMultiRoot = sharedPaths.size > 1

            // --- 优先路由 A：处理虚拟多根目录的主页渲染 ---
            if (isMultiRoot && (reqPath == "/" || reqPath.isEmpty())) {
                if (method == "POST") {
                    sendTextResponse(out, 403, "Forbidden", "虚拟主目录不支持直接上传，请点击并进入指定文件夹上传。")
                    return
                }
                val html = buildMultiRootHtml(sharedPaths)
                sendTextResponse(out, 200, "OK", html, "text/html; charset=utf-8")
                return
            }

            // --- 优先路由 B：优先拦截处理公网 POST 文件上传 ---
            if (method == "POST" && reqPath.startsWith("/upload")) {
                if (!isUploadAllowed) {
                    sendTextResponse(out, 403, "Forbidden", "手机端未开启公网上传文件权限。")
                    return
                }

                val query = parts[1].substringAfter("?", "")
                var filename = ""
                var destDirRel = ""
                if (query.isNotEmpty()) {
                    val queryParts = query.split("&")
                    for (qp in queryParts) {
                        val kv = qp.split("=")
                        if (kv.size == 2) {
                            val key = kv[0]
                            val value = URLDecoder.decode(kv[1], "UTF-8")
                            if (key == "filename") filename = value
                            if (key == "dir") destDirRel = value
                        }
                    }
                }

                if (filename.isEmpty()) {
                    sendTextResponse(out, 400, "Bad Request", "缺失上传参数 filename")
                    return
                }

                val uploadBaseDir: String
                val uploadCleanPath: String
                var cleanUploadRelDir = destDirRel.trimStart('/')
                if (cleanUploadRelDir.contains("..")) {
                    cleanUploadRelDir = cleanUploadRelDir.replace("..", "")
                }

                if (isMultiRoot) {
                    val firstSegment = cleanUploadRelDir.substringBefore("/")
                    val matchedPath = sharedPaths.firstOrNull { File(it).name == firstSegment }
                    if (matchedPath == null) {
                        sendTextResponse(out, 404, "Not Found", "定位上传盘符失败: $firstSegment")
                        return
                    }
                    uploadBaseDir = matchedPath
                    uploadCleanPath = cleanUploadRelDir.substringAfter("/", "")
                } else {
                    uploadBaseDir = sharedPaths[0]
                    uploadCleanPath = cleanUploadRelDir
                }

                var contentLength = -1L
                for (h in headers) {
                    if (h.lowercase().startsWith("content-length:")) {
                        contentLength = h.substringAfter(":").trim().toLongOrNull() ?: -1L
                    }
                }

                if (contentLength <= 0) {
                    sendTextResponse(out, 400, "Bad Request", "上传数据包长度 Content-Length 异常")
                    return
                }

                val finalDir = File(uploadBaseDir, uploadCleanPath)
                if (!finalDir.exists()) {
                    finalDir.mkdirs()
                }

                val destFile = File(finalDir, filename)
                LogManager.addLog("FileServer", "公网开始上传: $filename -> 目标路径: ${destFile.absolutePath} (大小: $contentLength 字节)")

                val fos = FileOutputStream(destFile)
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                while (totalBytesRead < contentLength) {
                    val remaining = contentLength - totalBytesRead
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val read = rawIn.read(buffer, 0, toRead)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    totalBytesRead += read
                }
                fos.flush()
                fos.close()

                LogManager.addLog("FileServer", "公网文件上传成功！落盘: ${destFile.name}")
                sendTextResponse(out, 200, "OK", "上传成功")
                return
            }

            // --- 常规路由 C：处理常规 GET 文件浏览和下载 ---
            var cleanPath = reqPath.trimStart('/')
            if (cleanPath.contains("..")) {
                cleanPath = cleanPath.replace("..", "")
            }

            val targetBaseDir: String
            val targetCleanPath: String

            if (isMultiRoot) {
                val firstSegment = cleanPath.substringBefore("/")
                val matchedPath = sharedPaths.firstOrNull { File(it).name == firstSegment }
                if (matchedPath == null) {
                    sendTextResponse(out, 404, "Not Found", "无法在虚拟多盘列表中定位到该盘符: $firstSegment")
                    return
                }
                targetBaseDir = matchedPath
                targetCleanPath = cleanPath.substringAfter("/", "")
            } else {
                targetBaseDir = sharedPaths[0]
                targetCleanPath = cleanPath
            }

            val targetFile = File(targetBaseDir, targetCleanPath)
            LogManager.addLog("FileServer", "请求: ${parts[1]} -> 目标物理绝对路径: ${targetFile.absolutePath} (存在: ${targetFile.exists()}, 文件夹: ${targetFile.isDirectory})")

            if (!targetFile.exists()) {
                sendTextResponse(out, 404, "Not Found", "您请求的文件或文件夹不存在")
                return
            }

            if (targetFile.isDirectory) {
                val fileList = targetFile.listFiles()
                if (fileList == null) {
                    LogManager.addLog("FileServer_Error", "⚠️ 物理读取权限受阻！")
                } else {
                    LogManager.addLog("FileServer", "成功读取文件夹，子项数量: ${fileList.size}")
                }

                val html = buildDirectoryHtml(reqPath, targetFile, isMultiRoot)
                sendTextResponse(out, 200, "OK", html, "text/html; charset=utf-8")
            } else {
                sendFileResponse(out, targetFile)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                out?.let { sendTextResponse(it, 500, "Internal Server Error", "内部错误: ${e.message}") }
            } catch (ex: Exception) { }
        } finally {
            try { rawIn?.close() } catch (e: Exception) {}
            try { out?.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun buildMultiRootHtml(paths: List<String>): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>cftunnel 虚拟多盘共享系统</title>")
        sb.append("<style>")
        sb.append("body { font-family: -apple-system, sans-serif; background: #0f0f14; color: #e4e4ef; padding: 20px; margin: 0; }")
        sb.append("h2 { color: #60a5fa; font-size: 18px; border-bottom: 1.5px solid #2a2a3a; padding-bottom: 10px; }")
        sb.append("ul { list-style: none; padding: 0; margin: 0; }")
        sb.append("li { padding: 14px; border-bottom: 1.5px solid #1c1c28; display: flex; align-items: center; }")
        sb.append("a { color: #60a5fa; text-decoration: none; font-size: 16px; display: block; flex: 1; font-weight: bold; }")
        sb.append("a:hover { color: #3b82f6; }")
        sb.append("</style></head><body>")
        sb.append("<h2>📁 cftunnel 虚拟多盘主页</h2>")
        sb.append("<ul>")
        for (p in paths) {
            val folderName = File(p).name
            sb.append("<li><a href='/$folderName'>📁 $folderName [本地虚拟挂载盘]</a></li>")
        }
        sb.append("</ul></body></html>")
        return sb.toString()
    }

    private fun buildDirectoryHtml(relative: String, dir: File, isMultiRoot: Boolean): String {
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
        sb.append(".back-btn { display: inline-block; padding: 6px 12px; background: #2a2a3a; border-radius: 6px; font-size: 13px; margin-bottom: 15px; color: #e4e4ef; text-decoration: none; }")
        sb.append(".upload-card { background: #161622; border: 1.5px dashed #3b82f6; border-radius: 8px; padding: 16px; text-align: center; margin-bottom: 20px; }")
        sb.append(".upload-btn { background: #3b82f6; color: white; border: none; padding: 8px 18px; border-radius: 6px; font-weight: bold; cursor: pointer; font-size: 13px; margin-top: 10px; }")
        sb.append(".upload-btn:hover { background: #2563eb; }")
        sb.append("</style></head><body>")

        val displayRelative = if (relative.isEmpty() || relative == "/") "/" else relative
        sb.append("<h2>📁 当前共享路径: $displayRelative</h2>")

        val relativeClean = if (relative == "/") "" else relative
        if (relativeClean.isNotEmpty()) {
            val parent = relativeClean.substringBeforeLast("/")
            val parentLink = if (parent.isEmpty()) "/" else parent
            sb.append("<a class='back-btn' href='$parentLink'>⬅️ 返回上级目录</a>")
        }

        if (isUploadAllowed) {
            sb.append("<div class='upload-card'>")
            sb.append("<h3 style='margin: 0 0 10px 0; font-size: 14px; color: #60a5fa;'>📤 上传文件到此文件夹</h3>")
            sb.append("<input type='file' id='fileInput' style='color: #8888a0; font-size: 13px;'><br/>")
            sb.append("<button class='upload-btn' onclick='uploadFile()'>开始安全上传</button>")
            sb.append("<div id='status' style='margin-top: 10px; font-size: 12px; color: #8888a0;'></div>")
            sb.append("</div>")

            sb.append("<script>")
            sb.append("function uploadFile() {")
            sb.append("  var fileInput = document.getElementById('fileInput');")
            sb.append("  if (fileInput.files.length === 0) { alert('请先选择要上传的文件！'); return; }")
            sb.append("  var file = fileInput.files[0];")
            sb.append("  var status = document.getElementById('status');")
            sb.append("  status.innerText = '正在上传: ' + file.name + ' (0%) ...';")
            sb.append("  var xhr = new XMLHttpRequest();")
            sb.append("  var targetUrl = '/upload?filename=' + encodeURIComponent(file.name) + '&dir=' + window.location.pathname;")
            sb.append("  xhr.open('POST', targetUrl, true);")
            sb.append("  xhr.upload.onprogress = function(e) {")
            sb.append("    if (e.lengthComputable) {")
            sb.append("      var percent = Math.round((e.loaded / e.total) * 100);")
            sb.append("      status.innerText = '正在上传: ' + file.name + ' (' + percent + '%) ...';")
            sb.append("    }")
            sb.append("  };")
            sb.append("  xhr.onload = function() {")
            sb.append("    if (xhr.status === 200) {")
            sb.append("      status.innerText = '✅ 上传成功！页面即将刷新...';")
            sb.append("      setTimeout(function() { location.reload(); }, 1000);")
            sb.append("    } else {")
            sb.append("      status.innerText = '❌ 上传失败: ' + xhr.responseText;")
            sb.append("    }")
            sb.append("  };")
            sb.append("  xhr.send(file);")
            sb.append("}")
            sb.append("</script>")
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
