package com.tocomic.app.data

import android.content.Context
import android.net.Uri
import com.tocomic.app.model.BoothState
import com.tocomic.app.model.CustomBooth
import com.tocomic.app.model.TripSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TripStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("trip_state", Context.MODE_PRIVATE)

    fun loadSnapshot(): TripSnapshot {
        val raw = prefs.getString(KEY_STATE, null) ?: return TripSnapshot()
        return runCatching { parseSnapshotJson(raw) }.getOrDefault(TripSnapshot())
    }

    fun saveSnapshot(snapshot: TripSnapshot) {
        prefs.edit().putString(KEY_STATE, buildExportJson(snapshot.normalized()).toString()).apply()
    }

    suspend fun exportTo(uri: Uri, snapshot: TripSnapshot) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(buildExportJson(snapshot.normalized()).toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun importFrom(uri: Uri): TripSnapshot = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }.orEmpty()
        parseSnapshotJson(text)
    }

    fun customBoothId(hallId: String, boothCode: String, name: String): String {
        val hall = normalizePunctuation(hallId).trim().uppercase().ifBlank { "UNKNOWN" }
        val code = normalizePunctuation(boothCode).trim().ifBlank { "待定" }
        val slug = normalizePunctuation(name)
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5._+-]+"), "-")
            .trim('-')
            .ifBlank { "booth" }
        return "custom-$hall-$code-$slug"
    }

    private fun buildExportJson(snapshot: TripSnapshot): JSONObject {
        val items = JSONArray()
        snapshot.states.toSortedMap().forEach { (id, state) ->
            items.put(JSONObject().apply {
                put("id", id)
                put("note", normalizePunctuation(state.note))
                put("customName", state.customName?.let(::normalizePunctuation) ?: JSONObject.NULL)
                put("planned", state.planned)
                put("planOrder", state.planOrder)
            })
        }

        val customBooths = JSONArray()
        snapshot.customBooths.sortedWith(compareBy<CustomBooth> { it.hallId }.thenBy { it.boothCode }.thenBy { it.name })
            .forEach { booth ->
                customBooths.put(JSONObject().apply {
                    put("id", booth.id)
                    put("hallId", normalizePunctuation(booth.hallId).trim().uppercase())
                    put("boothCode", normalizePunctuation(booth.boothCode).trim())
                    put("name", normalizePunctuation(booth.name).trim())
                    put("note", normalizePunctuation(booth.note))
                    put("planned", booth.planned)
                    put("planOrder", booth.planOrder)
                })
            }

        return JSONObject().apply {
            put("version", 2)
            put("items", items)
            put("customBooths", customBooths)
        }
    }

    private fun parseSnapshotJson(raw: String): TripSnapshot {
        if (raw.isBlank()) return TripSnapshot()
        val root = JSONObject(raw)
        return TripSnapshot(
            states = parseStates(root.optJSONArray("items") ?: JSONArray()),
            customBooths = parseCustomBooths(root.optJSONArray("customBooths") ?: JSONArray())
        ).normalized()
    }

    private fun parseStates(items: JSONArray): Map<String, BoothState> {
        val result = mutableMapOf<String, BoothState>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val normalizedId = normalizeStateId(id)
            val custom = item.optString("customName", "").takeIf { it.isNotBlank() && it != "null" }
            val state = BoothState(
                note = normalizePunctuation(item.optString("note", "")),
                customName = custom?.let(::normalizePunctuation),
                planned = item.optBoolean("planned", false),
                planOrder = item.optInt("planOrder", Int.MAX_VALUE)
            )
            if (normalizedId == id || normalizedId !in result) {
                result[normalizedId] = state
            }
        }
        return result
    }

    private fun parseCustomBooths(items: JSONArray): List<CustomBooth> {
        val result = linkedMapOf<String, CustomBooth>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val hallId = normalizePunctuation(item.optString("hallId")).trim().uppercase()
            val boothCode = normalizePunctuation(item.optString("boothCode")).trim()
            val name = normalizePunctuation(item.optString("name")).trim()
            if (hallId.isBlank() || boothCode.isBlank() || name.isBlank()) continue
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: customBoothId(hallId, boothCode, name)
            result[id] = CustomBooth(
                id = id,
                hallId = hallId,
                boothCode = boothCode,
                name = name,
                note = normalizePunctuation(item.optString("note", "")),
                planned = item.optBoolean("planned", false),
                planOrder = item.optInt("planOrder", Int.MAX_VALUE)
            )
        }
        return result.values.toList()
    }

    private fun TripSnapshot.normalized(): TripSnapshot {
        val normalizedStates = normalizeStateKeys(states).toMutableMap()
        val normalizedCustomBooths = customBooths.map { booth ->
            val hallId = normalizePunctuation(booth.hallId).trim().uppercase()
            val boothCode = normalizePunctuation(booth.boothCode).trim()
            val name = normalizePunctuation(booth.name).trim()
            booth.copy(
                id = booth.id.ifBlank { customBoothId(hallId, boothCode, name) },
                hallId = hallId,
                boothCode = boothCode,
                name = name,
                note = normalizePunctuation(booth.note)
            )
        }.filter { it.hallId.isNotBlank() && it.boothCode.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }

        normalizedCustomBooths.forEach { booth ->
            val current = normalizedStates[booth.id] ?: BoothState()
            normalizedStates[booth.id] = current.copy(
                note = current.note.ifBlank { booth.note },
                planned = current.planned || booth.planned,
                planOrder = minOf(current.planOrder, booth.planOrder)
            )
        }
        return TripSnapshot(normalizedStates, normalizedCustomBooths)
    }

    private fun normalizeStateKeys(states: Map<String, BoothState>): Map<String, BoothState> {
        val result = mutableMapOf<String, BoothState>()
        states.forEach { (id, state) ->
            val normalizedId = normalizeStateId(id)
            if (normalizedId == id || normalizedId !in result) {
                result[normalizedId] = state.copy(note = normalizePunctuation(state.note), customName = state.customName?.let(::normalizePunctuation))
            }
        }
        return result
    }

    private fun normalizeStateId(id: String): String {
        ID_ALIASES[id]?.let { return it }
        return id.replace(Regex("^(N[2-5])-(N[2-5])-(.+)$")) { match ->
            val hall = match.groupValues[1]
            val repeatedHall = match.groupValues[2]
            if (hall == repeatedHall) "$hall-${match.groupValues[3]}" else match.value
        }
    }

    fun normalizePunctuation(value: String): String = value
        .replace('\uFF0C', ',')
        .replace('\u3002', '.')
        .replace('\uFF1A', ':')
        .replace('\uFF1B', ';')
        .replace('\uFF01', '!')
        .replace('\uFF1F', '?')
        .replace('\uFF08', '(')
        .replace('\uFF09', ')')
        .replace('\u3010', '[')
        .replace('\u3011', ']')
        .replace('\u3001', ',')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')

    companion object {
        private const val KEY_STATE = "state_json"
        private val ID_ALIASES = mapOf(
            "E6-EDC-01" to "E6-EDC01",
            "E6-EDC-11" to "E6-EDC11",
            "N1-G901" to "N1-G691",
            "E7-S101" to "E7-S101-1"
        )
    }
}
