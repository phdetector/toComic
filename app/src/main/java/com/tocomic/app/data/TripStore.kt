package com.tocomic.app.data

import android.content.Context
import android.net.Uri
import com.tocomic.app.model.TripSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单个行程项目的本地存储与文件导入导出。
 *
 * JSON 编解码与合并规则全部在 [SnapshotCodec] 里（纯函数、可单测），
 * 这里只负责 SharedPreferences / ContentResolver 的读写。
 */
class TripStore(
    private val context: Context,
    private val projectId: String = "default"
) {
    private val prefs = context.getSharedPreferences("trip_state_$projectId", Context.MODE_PRIVATE)

    fun loadSnapshot(): TripSnapshot {
        val raw = prefs.getString("state_json", null) ?: return TripSnapshot()
        return runCatching { SnapshotCodec.decode(raw) }.getOrDefault(TripSnapshot())
    }

    fun saveSnapshot(snapshot: TripSnapshot) {
        prefs.edit()
            .putString("state_json", SnapshotCodec.encode(SnapshotCodec.normalize(snapshot)).toString())
            .apply()
    }

    fun saveSnapshotCommitted(snapshot: TripSnapshot) {
        prefs.edit()
            .putString("state_json", SnapshotCodec.encode(SnapshotCodec.normalize(snapshot)).toString())
            .commit()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun exportTo(uri: Uri, snapshot: TripSnapshot, keepFavorites: Boolean) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(exportJson(snapshot, keepFavorites).toByteArray(Charsets.UTF_8))
        }
    }

    fun exportJson(snapshot: TripSnapshot, keepFavorites: Boolean): String =
        SnapshotCodec.encode(
            SnapshotCodec.exportFilter(SnapshotCodec.normalize(snapshot), keepFavorites)
        ).toString(2)

    suspend fun importFrom(uri: Uri): TripSnapshot = withContext(Dispatchers.IO) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        descriptor?.use { file ->
            if (file.statSize > 5 * 1024 * 1024) error("import file too large")
        }
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }.orEmpty()
        SnapshotCodec.decode(text)
    }

    fun customBoothId(hallId: String, boothCode: String, name: String): String =
        SnapshotCodec.customBoothId(hallId, boothCode, name)

    fun normalizePunctuation(value: String): String = SnapshotCodec.normalizePunctuation(value)

    companion object {
        fun normalizePunctuation(value: String): String = SnapshotCodec.normalizePunctuation(value)
    }
}
