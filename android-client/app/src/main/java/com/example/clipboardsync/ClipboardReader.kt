package com.example.clipboardsync

import android.content.ClipboardManager
import android.content.Context
import android.util.Log

object ClipboardReader {
    private const val TAG = "ClipboardReader"

    fun readText(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
                val raw = item.text?.toString()?.trim().orEmpty()
                if (raw.isNotEmpty()) return raw
            }
            ""
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            ""
        }
    }
}