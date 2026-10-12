package com.example.clipboardsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private var serviceRunning = false
    private var ftpActive = false
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusIconContainer: android.widget.FrameLayout
    private lateinit var connectionTitle: TextView
    private lateinit var connectionSubtitle: TextView
    private lateinit var ftpUrlText: TextView
    private lateinit var transferProgressContainer: View
    private lateinit var transferStatusText: TextView
    private lateinit var transferProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var clipboardSwitch: SwitchCompat
    private lateinit var notifySwitch: SwitchCompat
    private lateinit var pcBatteryText: TextView
    private var serverUrlEditText: EditText? = null

    private val fileReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                ClipboardSyncService.ACTION_FILE_INCOMING -> {
                    val filename = intent.getStringExtra(ClipboardSyncService.EXTRA_FILENAME)
                        ?: intent.getStringExtra("filename")
                        ?: "Unknown file"
                    val size = intent.getLongExtra(ClipboardSyncService.EXTRA_FILE_SIZE, 0L)
                    showDownloadDialog(filename, size)
                }
                ClipboardSyncService.ACTION_PC_STATUS -> {
                    val battery = intent.getIntExtra("battery", -1)
                    val plugged = intent.getBooleanExtra("plugged", false)
                    if (battery != -1) {
                        pcBatteryText.visibility = View.VISIBLE
                        pcBatteryText.text = getString(R.string.pc_battery, battery)
                        if (plugged) pcBatteryText.append(" (🔌)")
                    }
                }
                ClipboardSyncService.ACTION_TRANSFER_PROGRESS -> {
                    val status = intent.getStringExtra(ClipboardSyncService.EXTRA_PROGRESS_STATUS).orEmpty()
                    val progress = intent.getIntExtra(ClipboardSyncService.EXTRA_PROGRESS_PERCENT, 0)
                    val isVisible = intent.getBooleanExtra(ClipboardSyncService.EXTRA_PROGRESS_VISIBLE, false)
                    
                    transferProgressContainer.visibility = if (isVisible) View.VISIBLE else View.GONE
                    transferStatusText.text = status
                    transferProgressBar.progress = progress
                }
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            updateConnectionUi()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents?.trim().orEmpty()
        if (text.startsWith("ws://") || text.startsWith("wss://")) {
            serverUrlEditText?.setText(text)
            saveServerUrl(text)
            if (clipboardSwitch.isChecked) startSyncService(silent = true)
            Toast.makeText(this, "URL from QR saved", Toast.LENGTH_SHORT).show()
        } else if (text.isNotEmpty()) {
            Toast.makeText(this, "QR is not a websocket URL", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()
        requestAllFilesAccessIfNeeded()

        setupMediaButtons()

        statusIcon = findViewById(R.id.statusIcon)
        statusIconContainer = findViewById(R.id.statusIconContainer)
        connectionTitle = findViewById(R.id.connectionTitle)
        connectionSubtitle = findViewById(R.id.connectionSubtitle)
        ftpUrlText = findViewById(R.id.ftpUrlText)
        transferProgressContainer = findViewById(R.id.transferProgressContainer)
        transferStatusText = findViewById(R.id.transferStatusText)
        transferProgressBar = findViewById(R.id.transferProgressBar)
        clipboardSwitch = findViewById(R.id.clipboardSwitch)
        notifySwitch = findViewById(R.id.notifySwitch)
        pcBatteryText = findViewById(R.id.pcBatteryText)

        findViewById<TextView>(R.id.appVersionText).text = "v${BuildConfig.VERSION_NAME}"
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { showServerSetupDialog() }

        val prefs = getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(ClipboardSyncService.KEY_SERVER_URL, ClipboardSyncService.DEFAULT_SERVER_URL).orEmpty()
        val syncEnabled = if (prefs.contains(KEY_SYNC_ENABLED)) {
            prefs.getBoolean(KEY_SYNC_ENABLED, false)
        } else {
            prefs.getString(ClipboardSyncService.KEY_STATUS, "Stopped") != "Stopped"
        }

        clipboardSwitch.isChecked = syncEnabled
        notifySwitch.isChecked = prefs.getBoolean(KEY_NOTIFY_FORWARD, false)

        if (!isSyncServiceRunning()) {
            if (syncEnabled) {
                prefs.edit()
                    .putBoolean(KEY_SYNC_ENABLED, false)
                    .putString(ClipboardSyncService.KEY_STATUS, "Stopped")
                    .apply()
                clipboardSwitch.setOnCheckedChangeListener(null)
                clipboardSwitch.isChecked = false
                clipboardSwitch.setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(KEY_SYNC_ENABLED, checked).apply()
                    if (checked) {
                        startSyncService()
                    } else {
                        if (!notifySwitch.isChecked) stopSyncService()
                    }
                }
            } else {
                prefs.edit().putString(ClipboardSyncService.KEY_STATUS, "Stopped").apply()
            }
        }

        updateFtpLabel(savedUrl)
        applyStatusVisual(prefs.getString(ClipboardSyncService.KEY_STATUS, "Stopped") ?: "Stopped")

        clipboardSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SYNC_ENABLED, checked).apply()
            if (checked) {
                startSyncService()
            } else {
                if (!notifySwitch.isChecked) stopSyncService()
            }
        }

        notifySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_NOTIFY_FORWARD, checked).apply()
            if (checked) {
                if (!isNotificationServiceEnabled()) {
                    Toast.makeText(this, R.string.enable_notification_access, Toast.LENGTH_LONG).show()
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                startSyncService(silent = true)
            } else {
                if (!clipboardSwitch.isChecked) stopSyncService()
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(ClipboardSyncService.ACTION_FILE_INCOMING)
            addAction(ClipboardSyncService.ACTION_PC_STATUS)
            addAction(ClipboardSyncService.ACTION_TRANSFER_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fileReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fileReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(statusPoller)
    }

    override fun onResume() {
        super.onResume()
        trySendClipboardIfForeground()
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(statusPoller)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(fileReceiver) } catch (_: Exception) {}
    }

    private fun trySendClipboardIfForeground() {
        if (!clipboardSwitch.isChecked) return
        val text = ClipboardReader.readText(this)
        if (text.isBlank()) return
        val intent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_SEND_TEXT
            putExtra(ClipboardSyncService.EXTRA_TEXT, text)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private var isPlaying = false

    private fun setupMediaButtons() {
        findViewById<View>(R.id.btnLockPC).setOnClickListener { sendMediaCommand("lock_pc") }
        findViewById<View>(R.id.btnOpenCalc).setOnClickListener { sendMediaCommand("open_calc") }
        findViewById<View>(R.id.btnOpenPC).setOnClickListener { sendMediaCommand("open_pc") }
        findViewById<View>(R.id.btnOpenBrowser).setOnClickListener { sendMediaCommand("open_browser") }

        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            if (isPlaying) {
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                btnPlayPause.setImageResource(R.drawable.ic_play)
            }
            sendMediaCommand("play_pause")
        }
        findViewById<View>(R.id.btnNext).setOnClickListener { sendMediaCommand("next") }
        findViewById<View>(R.id.btnPrev).setOnClickListener { sendMediaCommand("prev") }
        findViewById<View>(R.id.btnVolUp).setOnClickListener { sendMediaCommand("vol_up") }
        findViewById<View>(R.id.btnVolDown).setOnClickListener { sendMediaCommand("vol_down") }
    }

    private var lastMediaCommandAt = 0L

    private fun sendMediaCommand(action: String) {
        val now = System.currentTimeMillis()
        if (now - lastMediaCommandAt < 300) return // Дебаунс 300мс
        lastMediaCommandAt = now

        val intent = Intent(this, ClipboardSyncService::class.java).apply {
            this.action = "com.example.clipboardsync.ACTION_MEDIA_CONTROL"
            putExtra("media_action", action)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun updateConnectionUi() {
        val prefs = getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
        val status = prefs.getString(ClipboardSyncService.KEY_STATUS, "Stopped") ?: "Stopped"
        val url = getServerUrl()
        updateFtpLabel(url)

        serviceRunning = isSyncServiceRunning()

        applyStatusVisual(status)
        when {
            status == "Connected" -> {
                connectionTitle.text = getString(R.string.connected_to_pc)
                connectionSubtitle.text = getString(R.string.stable_network, Build.MODEL)
            }
            status.contains("Searching", ignoreCase = true) -> {
                connectionTitle.text = getString(R.string.connecting)
                connectionSubtitle.text = getString(R.string.waiting_network)
            }
            (status.contains("Start", ignoreCase = true) || status == "Connecting") && status != "Connected" -> {
                connectionTitle.text = getString(R.string.connecting)
                connectionSubtitle.text = getString(R.string.waiting_network)
            }
            status.startsWith("Error:") -> {
                connectionTitle.text = "Connection Error"
                connectionSubtitle.text = status.substringAfter("Error:").trim()
                pcBatteryText.visibility = View.GONE
            }
            else -> {
                connectionTitle.text = getString(R.string.disconnected)
                connectionSubtitle.text = getString(R.string.waiting_network)
                pcBatteryText.visibility = View.GONE
            }
        }
    }





    private fun updateFtpLabel(url: String) {
        val ftp = if (url.isNotBlank()) buildFtpUrl(url) else "ftp://—:2121"
        ftpUrlText.text = getString(R.string.ftp_access_url, ftp)
    }

    private fun buildFtpUrl(wsUrl: String): String {
        val host = wsUrl.replace("ws://", "").replace("wss://", "").substringBefore(":").substringBefore("/")
        return if (host.isNotEmpty()) "ftp://$host:2121" else "ftp://—:2121"
    }

    private fun getServerUrl(): String {
        return getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
            .getString(ClipboardSyncService.KEY_SERVER_URL, "").orEmpty()
    }

    private fun showServerSetupDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server_setup, null)
        serverUrlEditText = view.findViewById(R.id.serverUrlEditText)
        serverUrlEditText?.setText(getServerUrl())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_setup)
            .setView(view)
            .create()

        view.findViewById<android.widget.Button>(R.id.saveUrlButton).setOnClickListener {
            val url = serverUrlEditText?.text?.toString()?.trim().orEmpty()
            if (saveServerUrl(url)) {
                updateFtpLabel(url)
                dialog.dismiss()
            }
        }

        val manualContainer = view.findViewById<View>(R.id.manualContainer)
        view.findViewById<View>(R.id.manualToggleText).setOnClickListener {
            manualContainer.visibility = if (manualContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        view.findViewById<android.widget.Button>(R.id.scanQrButton).setOnClickListener {
            dialog.dismiss()
            openQrScanner()
        }
        dialog.show()
    }

    private fun startSyncService(silent: Boolean = false) {
        val url = getServerUrl()
        if (url.isNotBlank() && !saveServerUrl(url, silent)) {
            clipboardSwitch.isChecked = false
            return
        }

        getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_ENABLED, true).apply()

        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        if (url.isNotBlank()) {
            serviceIntent.putExtra(ClipboardSyncService.EXTRA_SERVER_URL, url)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            serviceRunning = true
        } catch (e: Exception) {
            if (!silent) Toast.makeText(this, "Cannot start: ${e.message}", Toast.LENGTH_LONG).show()
            clipboardSwitch.isChecked = false
        }
    }

    private fun stopSyncService() {
        stopService(Intent(this, ClipboardSyncService::class.java))
        serviceRunning = false
        getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SYNC_ENABLED, false)
            .putString(ClipboardSyncService.KEY_STATUS, "Stopped")
            .apply()
    }

    private fun isSyncServiceRunning(): Boolean = ClipboardSyncService.isServiceRunning()

    private fun saveServerUrl(url: String, silent: Boolean = false): Boolean {
        if (url.isEmpty()) {
            getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
                .edit().putString(ClipboardSyncService.KEY_SERVER_URL, "").apply()
            return true
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            if (!silent) Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
            return false
        }
        val normalized = normalizeWsUrlForSave(url)
        getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
            .edit().putString(ClipboardSyncService.KEY_SERVER_URL, normalized).apply()
        return true
    }

    private fun normalizeWsUrlForSave(url: String): String {
        var u = url.trim()
        val schemeEnd = u.indexOf("://") + 3
        if (schemeEnd < 3) return u
        val authorityAndPath = u.substring(schemeEnd)
        if (!authorityAndPath.contains('/') || authorityAndPath.substringAfter('/', "").isEmpty()) {
            u = "${u.trimEnd('/')}/ws"
        }
        return u
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ftp_url", text))
    }

    private fun showDownloadDialog(filename: String, sizeBytes: Long = 0L) {
        val sizeHint = if (sizeBytes > 0) {
            val kb = sizeBytes / 1024
            if (kb > 1024) " (${kb / 1024} MB)" else " (${kb} KB)"
        } else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Incoming File")
            .setMessage("Download '$filename'$sizeHint from PC?")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(this, ClipboardSyncService::class.java).apply {
                    action = ClipboardSyncService.ACTION_DOWNLOAD_CONFIRMED
                    putExtra(ClipboardSyncService.EXTRA_FILENAME, filename)
                    putExtra(ClipboardSyncService.EXTRA_FILE_SIZE, sizeBytes)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openQrScanner() {
        val options = ScanOptions()
        options.setPrompt("Scan pairing QR from PC")
        options.setBeepEnabled(true)
        options.setOrientationLocked(true)
        qrScanner.launch(options)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1002)
        }
    }


    private fun applyStatusVisual(status: String) {
        when {
            status == "Connected" -> {
                statusIcon.setImageResource(R.drawable.ic_check_white)
                statusIconContainer.setBackgroundResource(R.drawable.bg_status_icon_connected)
            }
            status.contains("Searching", ignoreCase = true) ||
                ((status.contains("Start", ignoreCase = true) || status == "Connecting") && status != "Connected") -> {
                statusIcon.setImageResource(R.drawable.ic_link_white)
                statusIconContainer.setBackgroundResource(R.drawable.bg_status_icon_connecting)
            }
            else -> {
                statusIcon.setImageResource(R.drawable.ic_close_white)
                statusIconContainer.setBackgroundResource(R.drawable.bg_status_icon_disconnected)
            }
        }
    }
    private fun isNotificationServiceEnabled(): Boolean {
        val names = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return names?.contains(packageName) == true
    }

    companion object {
        const val KEY_NOTIFY_FORWARD = "notify_forward_enabled"
        const val KEY_SYNC_ENABLED = "sync_enabled"
    }
}
