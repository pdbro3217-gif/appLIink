package com.example.clipboardsync

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationForwarderService : NotificationListenerService() {

    private var lastMusicKey = ""
    private var lastMusicAt = 0L
    private val musicLock = Any()

    private companion object {
        const val TAG = "NotifyForwarder"
        const val MUSIC_DEBOUNCE_MS = 5000L

        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.adservices.api",
        )

        val MESSENGER_PACKAGES = setOf(
            "org.telegram.messenger",
            "com.whatsapp",
            "com.viber.voip",
            "org.thoughtcrime.securesms",
            "com.vkontakte.android",
            "ru.ok.android",
            "com.facebook.orca",
            "com.discord",
        )

        val MUSIC_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.music",
            "ru.yandex.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.soundcloud.android",
            "com.deezer.android.app",
            "com.sec.android.app.music",
            "com.samsung.android.app.music",
            "com.miui.player",
            "com.huawei.music",
            "com.vk.music",
            "ru.rutube.app",
            "com.google.android.apps.podcasts",
        )
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification Listener Connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val prefs = getSharedPreferences(ClipboardSyncService.PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(MainActivity.KEY_NOTIFY_FORWARD, false)) return

            val packageName = sbn.packageName
            if (packageName == getPackageName() || packageName in IGNORED_PACKAGES) return

            val notification = sbn.notification
            val isMusic = isMusicNotification(packageName, notification)

            if (sbn.isOngoing || (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                if (!isMusic && packageName !in MESSENGER_PACKAGES) return
            }

            if (isMusic) {
                forwardMusic(sbn, packageName, notification)
                return
            }

            forwardRegularNotification(sbn, packageName, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding notification", e)
        }
    }

    private fun isMusicNotification(packageName: String, notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (packageName in MUSIC_PACKAGES) return true
        val p = packageName.lowercase()
        return p.contains("music") || p.contains("spotify") || p.contains("audio") ||
            p.contains("deezer") || p.contains("soundcloud") || p.contains("podcast")
    }

    private fun forwardMusic(sbn: StatusBarNotification, packageName: String, notification: Notification) {
        val extras = notification.extras
        var song = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        // Если в EXTRA_TEXT пусто, пробуем EXTRA_BIG_TEXT
        if (artist.isEmpty()) {
            artist = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        }

        // Если в EXTRA_TEXT (artist) попало что-то длинное (например, текст песни), обрезаем
        if (artist.length > 100) artist = artist.take(100) + "..."

        // Если artist пуст, а sub не пуст (часто исполнитель там), используем sub
        if (artist.isEmpty() && sub.isNotEmpty()) {
            artist = sub
        }

        // Часто плеер пишет "Исполнитель - Песня" в TITLE
        if (song.contains(" - ")) {
            val parts = song.split(" - ", limit = 2)
            if (artist.isEmpty() || artist == packageName) {
                artist = parts[0].trim()
                song = parts[1].trim()
            }
        }

        if (song.isEmpty() && artist.isEmpty()) return

        synchronized(musicLock) {
            val now = System.currentTimeMillis()
            // Глобальная дедупликация: игнорируем, если та же песня/исполнитель пришла слишком быстро
            // (защита от того, что разные плееры или система шлют одно и то же по очереди)
            val globalKey = "${norm(song)}|${norm(artist)}"
            if (globalKey == lastMusicKey && now - lastMusicAt < 3000) return

            lastMusicKey = globalKey
            lastMusicAt = now
        }

        val appName = appLabel(packageName)
        Log.d(TAG, "Music: [$appName] $song — $artist")

        val intent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_SEND_MUSIC
            putExtra(ClipboardSyncService.EXTRA_MUSIC_TITLE, song)
            putExtra(ClipboardSyncService.EXTRA_MUSIC_ARTIST, artist)
            putExtra(ClipboardSyncService.EXTRA_MUSIC_APP, appName)
        }
        startSyncService(intent)
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun musicContentKey(packageName: String, song: String, artist: String): String {
        val parts = listOf(norm(song), norm(artist)).filter { it.isNotEmpty() }.sorted()
        return "$packageName|${parts.joinToString("—")}"
    }

    private fun forwardRegularNotification(
        sbn: StatusBarNotification,
        packageName: String,
        notification: Notification,
    ) {
        if (isMusicNotification(packageName, notification)) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        }
        if (title.isEmpty() && text.isEmpty()) return

        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        if (lowerTitle.contains("clipboard") || lowerText.contains("clipboard") ||
            lowerTitle.contains("буфер") || lowerText.contains("буфер") ||
            lowerTitle.contains("скопировано") || lowerText.contains("скопировано")
        ) {
            return
        }

        val appName = appLabel(packageName)
        Log.d(TAG, "Forwarding: [$appName] $title")

        val intent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_SEND_NOTIFICATION
            putExtra(ClipboardSyncService.EXTRA_TEXT, "[$appName] $title: $text")
        }
        startSyncService(intent)
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun startSyncService(intent: Intent) {
        if (!ClipboardSyncService.isServiceRunning()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
