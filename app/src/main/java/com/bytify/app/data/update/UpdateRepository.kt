package com.bytify.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bytify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Pembaruan aplikasi dari GitHub Releases: cek versi, unduh APK, verifikasi
 * (paket, versi, tanda tangan sama), lalu buka pemasang Android.
 */
class UpdateRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME
    val isConfigured = BuildConfig.UPDATE_REPO.contains('/')

    /** @return info pembaruan jika ada versi lebih baru, null jika sudah terbaru. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        _state.value = UpdateState.Checking
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Bytify/$currentVersion")
            .build()
        try {
            val release = client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> error("Gagal memeriksa pembaruan (${response.code}).")
                    else -> json.decodeFromString<Release>(response.body!!.string())
                }
            }
            val apk = release?.assets?.firstOrNull { it.name.endsWith(".apk") }
            val version = release?.tagName?.removePrefix("v")
            if (release == null || apk == null || version == null || !isNewer(version, currentVersion)) {
                _state.value = UpdateState.UpToDate
                return@withContext null
            }
            UpdateInfo(version, release.body.orEmpty().trim(), apk.url, apk.size, release.htmlUrl)
                .also { _state.value = UpdateState.Available(it) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Tidak ada koneksi internet.")
            null
        }
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Bytify-${info.version}.apk")
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("Unduhan gagal (${response.code}).")
                val body = response.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastReported = 0f
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            val progress = if (total > 0) done.toFloat() / total else 0f
                            if (progress - lastReported >= 0.01f) {
                                lastReported = progress
                                _state.value = UpdateState.Downloading(info, progress)
                            }
                        }
                    }
                }
            }
            verify(file)
            _state.value = UpdateState.ReadyToInstall(info, file)
        } catch (e: Exception) {
            file.delete()
            _state.value = UpdateState.Failed(e.message ?: "Unduhan gagal.")
        }
    }

    /** Tolak APK yang bukan Bytify, bukan versi lebih baru, atau beda tanda tangan. */
    private fun verify(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error("File pembaruan rusak.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) error("File pembaruan bukan Bytify.")
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) error("Versi ini tidak lebih baru.")
        if (archive.signatureDigests() != installed.signatureDigests()) {
            error("Tanda tangan pembaruan tidak cocok. Unduh hanya dari rilis resmi Bytify.")
        }
    }

    /** Buka pemasang Android, atau minta izin "Instal aplikasi tidak dikenal" dulu. */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun PackageInfo.signatureDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") signatures
        }
        return signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    companion object {
        /** "1.10.0" > "1.9.2"; perbandingan per angka. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
