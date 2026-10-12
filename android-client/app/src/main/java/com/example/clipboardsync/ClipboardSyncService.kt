package com.example.clipboardsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.util.Base64
import org.json.JSONObject
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class ClipboardSyncService : Service() {
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var serverUrl: String
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var ignoreNextClipboardCallback = false
    private var reconnectScheduled = false
    private var isShuttingDown = false
    private var lastClipboardText: String = ""
    private lateinit var connectivityManager: android.net.ConnectivityManager
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var isDiscoveryStarted = false

    private val discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) { Log.d("NSD", "Discovery started") }
        override fun onServiceFound(service: android.net.nsd.NsdServiceInfo) {
            if (!service.serviceType.contains("_clipboardsync")) return
            stopNsdDiscovery()
            try {
                nsdManager?.resolveService(service, resolveListener)
            } catch (e: Exception) {
                Log.e("NSD", "resolveService failed", e)
                startNsdDiscovery()
            }
        }
        override fun onServiceLost(service: android.net.nsd.NsdServiceInfo) { Log.d("NSD", "Service lost") }
        override fun onDiscoveryStopped(regType: String) { isDiscoveryStarted = false }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscoveryStarted = false
            handler.postDelayed({ startNsdDiscovery() }, 3000)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isDiscoveryStarted = false }
    }

    private val resolveListener = object : android.net.nsd.NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Resolve failed: $errorCode")
            handler.post { startNsdDiscovery() }
        }
        override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
            val newUrl = webSocketUrlFromNsd(serviceInfo)
            if (newUrl.isBlank()) {
                handler.post { startNsdDiscovery() }
                return
            }
            handler.post {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val currentSavedUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty()
                if (normalizeWsUrl(currentSavedUrl) != newUrl) {
                    serverUrl = newUrl
                    prefs.edit().putString(KEY_SERVER_URL, newUrl).apply()
                    Log.i("NSD", "Found PC via NSD: $newUrl")
                }
                if (!isConnected) {
                    Log.d("NSD", "Server found via NSD, triggering connect")
                    connectWebSocket(force = true)
                }
                startNsdDiscovery()
            }
        }
    }

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            handler.post { if (!isConnected) connectWebSocket() }
        }
    }
    private var pendingSharedText: String? = null
    private val pendingNotifications = mutableListOf<String>()
    private var pendingMusicTitle: String? = null
    private var pendingMusicArtist: String? = null
    private var pendingMusicApp: String? = null
    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isConnected) return@OnPrimaryClipChangedListener
        if (ignoreNextClipboardCallback) {
            ignoreNextClipboardCallback = false
            return@OnPrimaryClipChangedListener
        }
        
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null && contentResolver.getType(uri)?.startsWith("image/") == true) {
                sendImageToSocket(uri)
                return@OnPrimaryClipChangedListener
            }
            
            val text = readClipboardText()
            if (text.isNotEmpty() && text != lastClipboardText) {
                sendClipboard(text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        startNsdDiscovery()
        lastClipboardText = readClipboardText()
        saveStatus("Starting...")
    }

    private var lastMediaCmdAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundSafe()

        if (intent != null) {
            when (intent.action) {
                ACTION_DOWNLOAD_CONFIRMED -> {
                    val filename = intent.getStringExtra(EXTRA_FILENAME)
                    if (filename != null) {
                        val size = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
                        downloadFromPc(filename, size)
                    }
                }
                ACTION_SEND_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (isConnected) sendClipboard(text) else pendingSharedText = text
                    }
                }
                ACTION_SEND_NOTIFICATION -> {
                    val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (isConnected) sendNotification(text) else {
                            synchronized(pendingNotifications) {
                                pendingNotifications.add(text)
                            }
                        }
                    }
                }
                ACTION_SEND_MUSIC -> {
                    val title = intent.getStringExtra(EXTRA_MUSIC_TITLE)?.trim().orEmpty()
                    val artist = intent.getStringExtra(EXTRA_MUSIC_ARTIST)?.trim().orEmpty()
                    val app = intent.getStringExtra(EXTRA_MUSIC_APP)?.trim().orEmpty()
                    if (title.isNotEmpty() || artist.isNotEmpty()) {
                        if (isConnected) sendMusicNowPlaying(title, artist, app)
                        else {
                            pendingMusicTitle = title
                            pendingMusicArtist = artist
                            pendingMusicApp = app
                        }
                    }
                }
                ACTION_UPLOAD_FILE -> {
                    intent.data?.let { uri ->
                        uploadFileToFtp(uri)
                        if (contentResolver.getType(uri)?.startsWith("image/") == true) {
                            sendImageToSocket(uri)
                        }
                    }
                }
                "com.example.clipboardsync.ACTION_MEDIA_CONTROL" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMediaCmdAt > 300) {
                        lastMediaCmdAt = now
                        val action = intent.getStringExtra("media_action")
                        if (action != null) {
                            if (isConnected) {
                                val payload = JSONObject().put("type", "media").put("action", action).toString()
                                webSocket?.send(payload)
                            } else {
                                connectWebSocket()
                            }
                        }
                    }
                }
            }
        }
        
        val overrideUrl = intent?.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        if (overrideUrl.isNotBlank() && overrideUrl != serverUrl) {
            serverUrl = overrideUrl
            if (isConnected || isConnecting) {
                webSocket?.cancel()
                isConnected = false
                isConnecting = false
            }
        }

        if (!isConnected && !isConnecting) {
            if (serverUrl.trim().isBlank()) {
                saveStatus("Searching for PC...")
                startNsdDiscovery()
            } else {
                connectWebSocket()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_SYNC_ENABLED, false)
            .putString(KEY_STATUS, "Stopped")
            .apply()
        requestStop(removeNotification = true)
        super.onTaskRemoved(rootIntent)
    }

    private fun requestStop(removeNotification: Boolean = false) {
        if (isShuttingDown) return
        isShuttingDown = true
        handler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        webSocket?.cancel()
        webSocket = null
        try {
            stopNsdDiscovery()
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        if (removeNotification) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}
        }
        stopSelf()
    }

    private fun webSocketUrlFromNsd(serviceInfo: android.net.nsd.NsdServiceInfo): String {
        val host = serviceInfo.host?.hostAddress ?: return ""
        val port = if (serviceInfo.port > 0) serviceInfo.port else 8765
        val formattedHost = if (host.contains(":")) "[$host]" else host
        var path = serviceInfo.attributes["path"]?.decodeToString()?.trim().orEmpty()
        if (path.isEmpty()) path = DEFAULT_WS_PATH
        if (!path.startsWith("/")) path = "/$path"
        return "ws://$formattedHost:$port$path"
    }

    private fun normalizeWsUrl(url: String): String {
        var u = url.trim()
        if (u.isBlank()) return ""
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "ws://$u"
        val schemeEnd = u.indexOf("://") + 3
        val authorityAndPath = u.substring(schemeEnd)
        val pathStart = authorityAndPath.indexOf('/')
        if (pathStart < 0) return "${u.trimEnd('/')}$DEFAULT_WS_PATH"
        val path = authorityAndPath.substring(pathStart)
        if (path.isEmpty() || path == "/") return "${u.trimEnd('/')}$DEFAULT_WS_PATH"
        return u
    }

    private fun stopNsdDiscovery() {
        if (!isDiscoveryStarted) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
        } finally {
            isDiscoveryStarted = false
        }
    }

    private fun processPendingShares() {
        pendingSharedText?.let { sendClipboard(it); pendingSharedText = null }
        synchronized(pendingNotifications) {
            pendingNotifications.forEach { sendNotification(it) }
            pendingNotifications.clear()
        }
        if (pendingMusicTitle != null || pendingMusicArtist != null) {
            sendMusicNowPlaying(
                pendingMusicTitle.orEmpty(),
                pendingMusicArtist.orEmpty(),
                pendingMusicApp.orEmpty()
            )
            pendingMusicTitle = null
            pendingMusicArtist = null
            pendingMusicApp = null
        }
        pendingImageBase64?.let { 
            val socket = webSocket
            if (socket != null && isConnected) {
                val payload = JSONObject().put("type", "image").put("data", it).toString()
                socket.send(payload)
                pendingImageBase64 = null
            }
        }
    }

    private fun startForegroundSafe() {
        try {
            val notification = buildNotification(getString(R.string.status_active))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) { stopSelf() }
    }

    private fun startNsdDiscovery() {
        if (isDiscoveryStarted) return
        try {
            nsdManager?.discoverServices("_clipboardsync._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscoveryStarted = true
        } catch (e: Exception) {
            Log.e("NSD", "Failed to start discovery", e)
        }
    }

    private fun connectWebSocket(force: Boolean = false) {
        if (isShuttingDown) return
        if (isConnected && !force) return
        
        if (isConnecting) {
            if (force) {
                webSocket?.cancel()
                isConnecting = false
            } else {
                return
            }
        }
        
        var urlToUse = normalizeWsUrl(serverUrl)
        if (urlToUse.isBlank()) {
            saveStatus("Searching for PC...")
            startNsdDiscovery()
            return
        }
        if (urlToUse != serverUrl) {
            serverUrl = urlToUse
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, urlToUse).apply()
        }

        isConnecting = true
        saveStatus("Connecting...")

        try {
            val request = Request.Builder().url(urlToUse).build()
            
            webSocket?.cancel()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    this@ClipboardSyncService.webSocket = webSocket
                    isConnected = true; isConnecting = false; reconnectScheduled = false
                    updateNotification("Connected to LinkApp")
                    saveStatus("Connected")
                    processPendingShares()
                    tryReadAndSendClipboard(force = true)
                    sendPhoneStatus()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val payload = JSONObject(text)
                        when (payload.optString("type")) {
                            "clipboard" -> updateClipboard(payload.optString("data"))
                            "image" -> updateImageClipboard(payload.optString("data"))
                            "notification" -> showIncomingNotification(payload.optString("data"))
                            "pc_status" -> {
                                val intent = Intent(ACTION_PC_STATUS).apply {
                                    putExtra("battery", payload.optInt("battery"))
                                    putExtra("plugged", payload.optBoolean("plugged"))
                                    setPackage(packageName)
                                }
                                sendBroadcast(intent)
                            }
                            "file_push" -> {
                                val filename = payload.optString("filename")
                                val intent = Intent(ACTION_FILE_INCOMING).apply {
                                    putExtra(EXTRA_FILENAME, filename)
                                    putExtra(EXTRA_FILE_SIZE, payload.optLong("size", 0L))
                                    setPackage(packageName)
                                }
                                sendBroadcast(intent)
                            }
                        }
                    } catch (e: Exception) {}
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    isConnected = false
                    isConnecting = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    isConnecting = false
                    if (!isShuttingDown) {
                        saveStatus("Disconnected")
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    isConnecting = false
                    val errorMsg = when {
                        response?.code == 403 -> "Server rejected (403)"
                        response?.code == 404 -> "Not found (404)"
                        t.message?.contains("Failed to connect") == true -> "PC Offline"
                        else -> t.message ?: "Failed"
                    }
                    saveStatus("Error: $errorMsg")
                    if (!isShuttingDown) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            isConnecting = false
            isConnected = false
            saveStatus("Error: ${e.message}")
            if (!isShuttingDown) scheduleReconnect()
        }
    }

    private fun isConnectedToWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun scheduleReconnect() {
        if (isShuttingDown || reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed({
            reconnectScheduled = false
            if (serverUrl.trim().isBlank()) startNsdDiscovery()
            connectWebSocket()
        }, 4000)
    }

    private fun sendClipboard(text: String) {
        if (text.isEmpty()) return
        val socket = webSocket
        if (socket != null && isConnected) {
            val payload = JSONObject().put("type", "clipboard").put("data", text).toString()
            val success = socket.send(payload)
            if (success) {
                lastClipboardText = text
            }
        } else {
            pendingSharedText = text
            if (!isConnecting) connectWebSocket()
        }
    }

    private fun sendNotification(text: String) {
        if (text.isEmpty()) return
        val socket = webSocket
        if (socket != null && isConnected) {
            val payload = JSONObject().put("type", "notification").put("data", text).toString()
            socket.send(payload)
        } else {
            synchronized(pendingNotifications) {
                pendingNotifications.add(text)
            }
            if (!isConnecting) connectWebSocket()
        }
    }

    private fun sendMusicNowPlaying(title: String, artist: String, app: String) {
        val socket = webSocket
        if (title.isEmpty() && artist.isEmpty()) return
        val dedupeKey = listOf(
            title.lowercase().trim(),
            artist.lowercase().trim(),
            app.lowercase().trim(),
        ).filter { it.isNotEmpty() }.sorted().joinToString("|")
        val now = System.currentTimeMillis()
        synchronized(ClipboardSyncService::class.java) {
            if (dedupeKey == lastSentMusicKey && now - lastSentMusicAt < MUSIC_SEND_DEBOUNCE_MS) return
            lastSentMusicKey = dedupeKey
            lastSentMusicAt = now
        }
        val payload = JSONObject().apply {
            put("type", "music_now")
            put("title", title)
            put("artist", artist)
            put("app", app.ifEmpty { "Телефон" })
        }.toString()
        if (socket != null && isConnected) {
            socket.send(payload)
        } else {
            pendingMusicTitle = title
            pendingMusicArtist = artist
            pendingMusicApp = app
            if (!isConnecting) connectWebSocket()
        }
    }

    private fun sendImageToSocket(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val socket = webSocket
                    if (socket != null && isConnected) {
                        val payload = JSONObject().put("type", "image").put("data", base64).toString()
                        socket.send(payload)
                    } else {
                        pendingImageBase64 = base64
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun broadcastProgress(status: String, progress: Int, isVisible: Boolean) {
        val intent = Intent(ACTION_TRANSFER_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_STATUS, status)
            putExtra(EXTRA_PROGRESS_PERCENT, progress)
            putExtra(EXTRA_PROGRESS_VISIBLE, isVisible)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        broadcastProgress("❌ $message", 0, true)
        Log.e("FTP", message)
    }

    private fun hideProgressDelayed() {
        handler.postDelayed({ broadcastProgress("", 0, false) }, 3000)
    }

    fun uploadFileToFtp(uri: Uri) {
        Thread {
            val filename = FtpTransfer.sanitizeForUpload(getFileName(uri))
            val tmp = File(cacheDir, "linkapp_up_${System.nanoTime()}")
            try {
                broadcastProgress("Connecting...", 0, true)
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw Exception("Cannot open file")
                if (tmp.length() <= 0L) throw Exception("File is empty")

                var bytes = tmp.length()
                try {
                    bytes = PcFileTransfer.uploadFile(serverUrl, filename, tmp)
                    broadcastProgress("Uploading… 100%", 100, true)
                } catch (httpErr: Exception) {
                    tmp.inputStream().use { stream ->
                        val cfg = FtpTransfer.configFromWebSocketUrl(serverUrl)
                        FtpTransfer.upload(cfg, filename, stream) { pct ->
                            broadcastProgress("Uploading… $pct%", pct, true)
                        }
                    }
                }
                broadcastProgress("✅ Success: $filename", 100, true)
                webSocket?.send(
                    JSONObject().put("type", "file_notify").put("filename", filename).put("size", bytes).toString()
                )
            } catch (e: Exception) {
                broadcastError(e.message ?: "Upload error")
            } finally {
                tmp.delete()
                hideProgressDelayed()
            }
        }.start()
    }

    private fun downloadFromPc(filename: String, expectedSize: Long) {
        Thread {
            val remoteName = FtpTransfer.basenameOnly(filename)
            val tmp = File(cacheDir, "linkapp_dl_${System.nanoTime()}")
            try {
                broadcastProgress("Downloading...", 0, true)
                var bytes = 0L
                try {
                    bytes = PcFileTransfer.downloadToFile(serverUrl, remoteName, tmp)
                    broadcastProgress("Downloading… 100%", 100, true)
                } catch (httpErr: Exception) {
                    tmp.delete()
                    val cfg = FtpTransfer.configFromWebSocketUrl(serverUrl)
                    bytes = FtpTransfer.downloadToFile(cfg, remoteName, tmp) { pct ->
                        broadcastProgress("Downloading… $pct%", pct, true)
                    }
                }
                publishToDownloads(tmp, remoteName)
                broadcastProgress("✅ Downloaded: $remoteName", 100, true)
            } catch (e: Exception) {
                broadcastError(e.message ?: "Download error")
            } finally {
                tmp.delete()
                hideProgressDelayed()
            }
        }.start()
    }

    private fun publishToDownloads(source: File, displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LinkApp")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Cannot create download entry")
            contentResolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LinkApp")
            if (!dir.exists()) dir.mkdirs()
            source.copyTo(File(dir, displayName), overwrite = true)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) {
                    val stringValue = cursor.getString(index)
                    if (!stringValue.isNullOrEmpty()) name = stringValue
                }
            }
        } catch (e: Exception) {}
        return name
    }

    private fun updateClipboard(text: String) {
        handler.post {
            try {
                ignoreNextClipboardCallback = true
                val clip = ClipData.newPlainText("synced_text", text)
                clipboardManager.setPrimaryClip(clip)
                lastClipboardText = text
            } catch (e: Exception) {}
        }
    }

    private fun updateImageClipboard(base64Data: String) {
        if (base64Data.isEmpty()) return
        handler.post {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val file = File(cacheDir, "pc_clipboard_image.png")
                file.outputStream().use { it.write(bytes) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                ignoreNextClipboardCallback = true
                val clip = ClipData.newUri(contentResolver, "PC Image", uri)
                clipboardManager.setPrimaryClip(clip)
            } catch (e: Exception) {}
        }
    }

    private fun readClipboardText(): String = ClipboardReader.readText(this)

    private fun tryReadAndSendClipboard(force: Boolean = false) {
        val text = readClipboardText()
        if (force || (text.isNotEmpty() && text != lastClipboardText)) sendClipboard(text)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(serviceChannel)
            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.notification_from_pc) }
            manager.createNotificationChannel(incomingChannel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        
        // Кнопки управления мультимедиа для уведомления
        val prevIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = "com.example.clipboardsync.ACTION_MEDIA_CONTROL"
            putExtra("media_action", "prev")
        }
        val prevPi = PendingIntent.getService(this, 1, prevIntent, flags)

        val nextIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = "com.example.clipboardsync.ACTION_MEDIA_CONTROL"
            putExtra("media_action", "next")
        }
        val nextPi = PendingIntent.getService(this, 3, nextIntent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkApp Active")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_link)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Добавляем кнопки
        builder.addAction(R.drawable.ic_skip_prev, "Prev", prevPi)
        builder.addAction(R.drawable.ic_skip_next, "Next", nextPi)

        return builder.build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun showIncomingNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_link)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun sendPhoneStatus() {
        if (!isConnected) return
        try {
            val batteryStatus: Intent? = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { registerReceiver(null, it) }
            val level: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val payload = JSONObject().apply {
                put("type", "phone_status")
                put("battery", batteryPct)
                put("charging", isCharging)
                put("model", Build.MODEL)
            }.toString()
            webSocket?.send(payload)
        } catch (e: Exception) {}
    }

    private fun saveStatus(status: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply()
    }

    override fun onDestroy() {
        isRunning = false
        isShuttingDown = true
        saveStatus("Stopped")
        handler.removeCallbacksAndMessages(null)
        try {
            stopNsdDiscovery()
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        webSocket?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        private var isRunning = false

        fun isServiceRunning(): Boolean = isRunning

        const val ACTION_SEND_TEXT = "com.example.clipboardsync.ACTION_SEND_TEXT"
        const val ACTION_UPLOAD_FILE = "com.example.clipboardsync.ACTION_UPLOAD_FILE"
        const val ACTION_SEND_NOTIFICATION = "com.example.clipboardsync.ACTION_SEND_NOTIFICATION"
        const val ACTION_SEND_MUSIC = "com.example.clipboardsync.ACTION_SEND_MUSIC"
        const val EXTRA_MUSIC_TITLE = "extra_music_title"
        const val EXTRA_MUSIC_ARTIST = "extra_music_artist"
        const val EXTRA_MUSIC_APP = "extra_music_app"
        const val ACTION_FILE_INCOMING = "com.example.clipboardsync.ACTION_FILE_INCOMING"
        const val ACTION_PC_STATUS = "com.example.clipboardsync.ACTION_PC_STATUS"
        const val ACTION_DOWNLOAD_CONFIRMED = "com.example.clipboardsync.ACTION_DOWNLOAD_CONFIRMED"
        const val ACTION_TRANSFER_PROGRESS = "com.example.clipboardsync.ACTION_TRANSFER_PROGRESS"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_FILE_SIZE = "file_size"
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_PROGRESS_STATUS = "extra_progress_status"
        const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        const val EXTRA_PROGRESS_VISIBLE = "extra_progress_visible"
        const val PREFS_NAME = "clipboard_sync_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_STATUS = "status"
        const val KEY_APP_FILTER_ENABLED = "app_filter_enabled"
        const val KEY_ALLOWED_APPS = "allowed_apps"
        const val DEFAULT_SERVER_URL = ""
        private const val DEFAULT_WS_PATH = "/ws"
        private const val CHANNEL_ID = "clipboard_sync_channel"
        private const val CHANNEL_INCOMING_ID = "linkapp_incoming"
        private const val NOTIFICATION_ID = 101
        private const val MUSIC_SEND_DEBOUNCE_MS = 5000L

        @Volatile
        private var lastSentMusicKey = ""

        @Volatile
        private var lastSentMusicAt = 0L
    }
}
