package com.tocomic.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateInstaller {
    private const val MAX_APK_BYTES = 300L * 1024 * 1024
    private const val TIMEOUT_MS = 30_000

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean = runCatching {
        val uri = Uri.parse("package:${context.packageName}")
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
    }.isSuccess

    suspend fun download(context: Context, url: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
            val target = File(dir, "update-${System.currentTimeMillis()}.apk")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                val code = connection.responseCode
                if (code !in 200..299) error("download failed: $code")
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_APK_BYTES) error("apk too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (target.length() == 0L) error("empty apk")
            target
        }
    }

    fun verifySignature(context: Context, apk: File): Boolean {
        val expected = currentSignerHashes(context) ?: return false
        val archive = apkSignerHashes(context, apk) ?: return false
        return archive.isNotEmpty() && expected.any { it in archive }
    }

    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.isSuccess

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    private fun currentSignerHashes(context: Context): Set<String>? {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            signing.apkContentsSigners.mapNotNull { it?.let { cert -> sha256(cert) } }.toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.mapNotNull { it?.let { cert -> sha256(cert) } }?.toSet()
        }
    }

    private fun apkSignerHashes(context: Context, apk: File): Set<String>? {
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
        }.getOrNull() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            signing.apkContentsSigners.mapNotNull { it?.let { cert -> sha256(cert) } }.toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.mapNotNull { it?.let { cert -> sha256(cert) } }?.toSet()
        }
    }

    private fun sha256(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
