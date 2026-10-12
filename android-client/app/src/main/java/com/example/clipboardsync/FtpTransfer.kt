package com.example.clipboardsync

import android.util.Log
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.io.CopyStreamAdapter
import org.apache.commons.net.io.Util
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object FtpTransfer {
    private const val TAG = "FtpTransfer"
    private const val DEFAULT_PORT = 2121
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val DATA_TIMEOUT_MS = 120_000
    private const val BUFFER_SIZE = 1024 * 1024

    data class Config(
        val host: String,
        val port: Int = DEFAULT_PORT,
        val user: String = "anonymous",
        val password: String = "anonymous@",
    )

    fun configFromWebSocketUrl(wsUrl: String): Config {
        val host = wsUrl
            .removePrefix("ws://")
            .removePrefix("wss://")
            .substringBefore(":")
            .substringBefore("/")
            .trim()
        return Config(host = host)
    }

    fun basenameOnly(name: String): String {
        return name.substringAfterLast('/').substringAfterLast('\\').trim()
    }

    fun sanitizeForUpload(name: String): String {
        val base = basenameOnly(name)
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
        return cleaned.ifEmpty { "file_${System.currentTimeMillis()}" }
    }

    fun upload(config: Config, filename: String, input: InputStream, onProgress: ((Int) -> Unit)? = null) {
        withClient(config, passiveFirst = true) { client ->
            attachProgress(client, onProgress)
            if (!client.storeFile(filename, input)) {
                throw IOException("Upload failed: ${client.replyString.trim()}")
            }
            onProgress?.invoke(100)
        }
    }

    @Throws(IOException::class)
    fun downloadToFile(
        config: Config,
        filename: String,
        dest: File,
        onProgress: ((Int) -> Unit)? = null,
    ): Long {
        val remoteName = basenameOnly(filename)
        var lastError: IOException? = null
        for (passiveFirst in listOf(true, false)) {
            try {
                return withClient(config, passiveFirst = passiveFirst) { client ->
                    attachProgress(client, onProgress)
                    streamDownload(client, resolveRemoteName(client, remoteName), dest, onProgress)
                }
            } catch (e: IOException) {
                lastError = e
                dest.delete()
                Log.w(TAG, "FTP ${if (passiveFirst) "passive" else "active"} failed: ${e.message}")
            }
        }
        throw lastError ?: IOException("FTP download failed")
    }

    private fun streamDownload(
        client: FTPClient,
        remoteName: String,
        dest: File,
        onProgress: ((Int) -> Unit)?,
    ): Long {
        val input = client.retrieveFileStream(remoteName)
            ?: throw IOException(client.replyString.trim().ifEmpty { "Cannot open $remoteName" })
        try {
            dest.outputStream().use { out -> Util.copyStream(input, out) }
        } finally {
            input.close()
        }
        if (!client.completePendingCommand()) {
            throw IOException("FTP incomplete: ${client.replyString.trim()}")
        }
        val size = dest.length()
        if (size <= 0L) throw IOException("FTP returned empty file")
        onProgress?.invoke(100)
        return size
    }

    private fun resolveRemoteName(client: FTPClient, requested: String): String {
        if (requested.isEmpty()) throw IOException("Empty filename")
        val exact = client.listNames(requested)
        if (!exact.isNullOrEmpty()) return requested
        val all = client.listNames()?.filter { it.isNotBlank() && it != "." && it != ".." } ?: emptyList()
        return all.firstOrNull { it == requested }
            ?: all.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: requested
    }

    private fun attachProgress(client: FTPClient, onProgress: ((Int) -> Unit)?) {
        if (onProgress == null) return
        client.setCopyStreamListener(object : CopyStreamAdapter() {
            override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                if (streamSize > 0) {
                    onProgress(((totalBytesTransferred * 100) / streamSize).toInt().coerceIn(0, 99))
                }
            }
        })
    }

    private inline fun <T> withClient(
        config: Config,
        passiveFirst: Boolean,
        block: (FTPClient) -> T,
    ): T {
        if (config.host.isBlank()) throw IOException("PC IP address is unknown")
        val client = FTPClient()
        try {
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = DATA_TIMEOUT_MS
            client.setControlEncoding("UTF-8")
            client.connect(config.host, config.port)
            client.setRemoteVerificationEnabled(false)
            try {
                client.setAutodetectUTF8(true)
            } catch (_: Exception) {
            }
            if (!client.login(config.user, config.password)) {
                throw IOException("FTP login failed: ${client.replyString.trim()}")
            }
            if (passiveFirst) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.setBufferSize(BUFFER_SIZE)
            return block(client)
        } finally {
            disconnectQuietly(client)
        }
    }

    private fun disconnectQuietly(client: FTPClient) {
        try {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "disconnect: ${e.message}")
        }
    }
}
