package com.cj2026.planner.data

import android.content.Context
import android.net.Uri
import com.cj2026.planner.model.Booth
import com.cj2026.planner.model.BoothState
import com.cj2026.planner.model.CustomBooth
import com.cj2026.planner.model.PlannerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PlannerStore(
    private val context: Context,
    private val projectId: String = "default"
) {
    private val prefs = context.getSharedPreferences("planner_state_$projectId", Context.MODE_PRIVATE)

    fun loadSnapshot(): PlannerSnapshot {
        val raw = prefs.getString("state_json", null) ?: return PlannerSnapshot()
        return runCatching { parseSnapshotJson(raw) }.getOrDefault(PlannerSnapshot())
    }

    fun saveSnapshot(snapshot: PlannerSnapshot) {
        prefs.edit().putString("state_json", buildExportJson(snapshot.normalized()).toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun exportTo(uri: Uri, snapshot: PlannerSnapshot, keepFavorites: Boolean) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(exportJson(snapshot, keepFavorites).toByteArray(Charsets.UTF_8))
        }
    }

    fun exportJson(snapshot: PlannerSnapshot, keepFavorites: Boolean): String =
        buildExportJson(snapshot.normalized().forExport(keepFavorites)).toString(2)

    suspend fun importFrom(uri: Uri): PlannerSnapshot = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > 5 * 1024 * 1024) error("import file too large")
            bytes.toString(Charsets.UTF_8)
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

    private fun buildExportJson(snapshot: PlannerSnapshot): JSONObject {
        val items = JSONArray()
        snapshot.states.toSortedMap().forEach { (id, state) ->
            items.put(JSONObject().apply {
                put("id", id)
                put("note", normalizePunctuation(state.note))
                put("customName", state.customName?.let(::normalizePunctuation) ?: JSONObject.NULL)
                put("planned", state.planned)
                put("planOrder", state.planOrder)
                put("startTime", state.startTime)
                put("endTime", state.endTime)
                put("completed", state.completed)
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
                    put("startTime", booth.startTime)
                    put("endTime", booth.endTime)
                    put("completed", booth.completed)
                })
            }

        val booths = JSONArray()
        snapshot.booths.sortedWith(compareBy<Booth> { it.hallId }.thenBy { it.boothCode }.thenBy { it.name })
            .forEach { booth ->
                booths.put(JSONObject().apply {
                    put("id", booth.id)
                    put("hallId", booth.hallId)
                    put("boothCode", booth.boothCode)
                    put("name", booth.name)
                    put("logoUrl", booth.logoUrl ?: JSONObject.NULL)
                })
            }

        return JSONObject().apply {
            put("version", 4)
            put("items", items)
            put("customBooths", customBooths)
            put("booths", booths)
        }
    }

    private fun parseSnapshotJson(raw: String): PlannerSnapshot {
        if (raw.isBlank()) return PlannerSnapshot()
        val root = JSONObject(raw)
        return PlannerSnapshot(
            states = parseStates(root.optJSONArray("items") ?: JSONArray()),
            customBooths = parseCustomBooths(root.optJSONArray("customBooths") ?: JSONArray()),
            booths = parseBooths(root.optJSONArray("booths") ?: JSONArray())
        ).normalized()
    }

    private fun parseBooths(items: JSONArray): List<Booth> {
        val result = linkedMapOf<String, Booth>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val hallId = item.optString("hallId").trim().uppercase()
            val boothCode = item.optString("boothCode").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || hallId.isBlank() || boothCode.isBlank() || name.isBlank()) continue
            result[id] = Booth(
                id = id,
                hallId = hallId,
                boothCode = boothCode,
                name = name,
                logoUrl = item.optString("logoUrl", "").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        return result.values.toList()
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
                planOrder = item.optInt("planOrder", Int.MAX_VALUE),
                startTime = item.optString("startTime", ""),
                endTime = item.optString("endTime", ""),
                completed = item.optBoolean("completed", false)
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
                planOrder = item.optInt("planOrder", Int.MAX_VALUE),
                startTime = item.optString("startTime", ""),
                endTime = item.optString("endTime", ""),
                completed = item.optBoolean("completed", false)
            )
        }
        return result.values.toList()
    }

    private fun PlannerSnapshot.normalized(): PlannerSnapshot {
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
                planOrder = minOf(current.planOrder, booth.planOrder),
                startTime = current.startTime.ifBlank { booth.startTime },
                endTime = current.endTime.ifBlank { booth.endTime },
                completed = current.completed || booth.completed
            )
        }
        return PlannerSnapshot(normalizedStates, normalizedCustomBooths, booths)
    }

    private fun PlannerSnapshot.forExport(keepFavorites: Boolean): PlannerSnapshot {
        val exportedStates = states.mapValues { (_, state) ->
            state.copy(
                planned = keepFavorites && state.planned,
                completed = false
            )
        }
        val exportedCustomBooths = customBooths.map { booth ->
            booth.copy(
                planned = keepFavorites && booth.planned,
                completed = false
            )
        }
        return PlannerSnapshot(exportedStates, exportedCustomBooths, booths)
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

        private val ID_ALIASES = mapOf(
            "E6-EDC-01" to "E6-EDC01",
            "E6-EDC-11" to "E6-EDC11",
            "N1-G901" to "N1-G691",
            "E7-S101" to "E7-S101-1"
        )
    }
}
