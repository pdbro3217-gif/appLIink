package com.example.clipboardsync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ShareToSyncActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val currentIntent = intent ?: run { finish(); return }

        val prefs = getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(ClipboardSyncService.KEY_SERVER_URL, "")?.trim() ?: ""
        
        if (savedUrl.isEmpty()) {
            Toast.makeText(this, "Setup Server URL first!", Toast.LENGTH_LONG).show()
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(mainIntent)
            finish(); return
        }

        // 1. Обработка ТЕКСТА
        var sharedText: String? = currentIntent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText == null) {
            sharedText = currentIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        if (!sharedText.isNullOrEmpty()) {
            val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
                action = ClipboardSyncService.ACTION_SEND_TEXT
                putExtra(ClipboardSyncService.EXTRA_TEXT, sharedText)
                putExtra(ClipboardSyncService.EXTRA_SERVER_URL, savedUrl)
            }
            startServiceCompat(serviceIntent)
            
            // Открываем главное приложение
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(mainIntent)
            
            finish()
            return
        }

        // 2. Обработка ФАЙЛОВ (Одиночных и Множественных)
        val uris = mutableListOf<Uri>()
        
        if (currentIntent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                currentIntent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { uris.add(it) }
        } else if (currentIntent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                currentIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.let { uris.addAll(it) }
        }

        if (uris.isNotEmpty()) {
            for (uri in uris) {
                val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
                    action = ClipboardSyncService.ACTION_UPLOAD_FILE
                    data = uri
                    clipData = android.content.ClipData.newRawUri(null, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(ClipboardSyncService.EXTRA_SERVER_URL, savedUrl)
                }
                startServiceCompat(serviceIntent)
            }
            
            finish()
            return
        }
        
        Toast.makeText(this, "No file found to share", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
