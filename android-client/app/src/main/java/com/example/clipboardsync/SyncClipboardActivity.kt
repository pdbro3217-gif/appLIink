package com.example.clipboardsync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Reads clipboard while app is in foreground — the only reliable way on Android 10+. */
class SyncClipboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
        val url = prefs.getString(ClipboardSyncService.KEY_SERVER_URL, "").orEmpty().trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.url_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val text = ClipboardReader.readText(this)
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val serviceIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_SEND_TEXT
            putExtra(ClipboardSyncService.EXTRA_TEXT, text)
            putExtra(ClipboardSyncService.EXTRA_SERVER_URL, url)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, R.string.clipboard_sent, Toast.LENGTH_SHORT).show()
        finish()
    }
}