package com.example.clipboardsync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** HTTP file exchange on the same port as WebSocket (8765). Avoids FTP passive mode issues. */
object PcFileTransfer {
    private const val TAG = "PcFileTransfer"
    private const val HTTP_PORT = 8765

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun httpHostFromWs(wsUrl: String): String = FtpTransfer.configFromWebSocketUrl(wsUrl).host

    fun downloadUrl(wsUrl: String, filename: String): String {
        val name = FtpTransfer.basenameOnly(filename)
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
        return "http://${httpHostFromWs(wsUrl)}:$HTTP_PORT/files/$encoded"
    }

    fun uploadUrl(wsUrl: String): String =
        "http://${httpHostFromWs(wsUrl)}:$HTTP_PORT/files"

    @Throws(IOException::class)
    fun downloadToFile(wsUrl: String, filename: String, dest: File): Long {
        val url = downloadUrl(wsUrl, filename)
        Log.d(TAG, "HTTP GET $url")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty HTTP response")
            dest.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        val size = dest.length()
        if (size <= 0L) throw IOException("Downloaded file is empty")
        return size
    }

    @Throws(IOException::class)
    fun uploadFile(wsUrl: String, filename: String, file: File): Long {
        if (file.length() <= 0L) throw IOException("Nothing to upload")
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val part = MultipartBody.Part.createFormData("file", filename, body)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(part)
            .build()
        val request = Request.Builder()
            .url(uploadUrl(wsUrl))
            .post(multipart)
            .build()
        Log.d(TAG, "HTTP POST ${uploadUrl(wsUrl)} ($filename, ${file.length()} bytes)")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
        }
        return file.length()
    }
}
