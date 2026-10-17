package com.tocomic.app.data

import com.tocomic.app.model.Booth
import com.tocomic.app.model.BoothState
import com.tocomic.app.model.CustomBooth
import com.tocomic.app.model.HallMeta
import com.tocomic.app.model.TripSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快照的 JSON 编解码与合并规则。
 *
 * 这里全部是纯函数，不依赖 Android Context，因此可以在 JVM 单测里直接验证
 * 「导出是否保住收藏」「导入是否会把已有收藏清零」这类语义。
 */
object SnapshotCodec {
    const val VERSION = 5
    const val MAX_IMPORT_ITEMS = 10_000
    const val MAX_JSON_DEPTH = 64
    const val MAX_HALL_TITLE = 40
    const val MAX_HALL_SUBTITLE = 100

    private val ID_ALIASES = mapOf(
        "E6-EDC-01" to "E6-EDC01",
        "E6-EDC-11" to "E6-EDC11",
        "N1-G901" to "N1-G691",
        "E7-S101" to "E7-S101-1"
    )

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

    // ---------- 编码 ----------

    fun encode(snapshot: TripSnapshot): JSONObject {
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

        val halls = JSONArray()
        snapshot.hallMeta.toSortedMap().forEach { (id, meta) ->
            halls.put(JSONObject().apply {
                put("id", id)
                put("title", normalizePunctuation(meta.title).trim().take(MAX_HALL_TITLE))
                put("subtitle", normalizePunctuation(meta.subtitle).trim().take(MAX_HALL_SUBTITLE))
            })
        }

        return JSONObject().apply {
            put("version", VERSION)
            put("items", items)
            put("customBooths", customBooths)
            put("booths", booths)
            put("halls", halls)
        }
    }

    /** 导出时按选择过滤：不保留收藏则清空 planned；两种模式都强制把完成状态重置为未完成。 */
    fun exportFilter(snapshot: TripSnapshot, keepFavorites: Boolean): TripSnapshot {
        val exportedStates = snapshot.states.mapValues { (_, state) ->
            state.copy(
                planned = keepFavorites && state.planned,
                completed = false
            )
        }
        val exportedCustomBooths = snapshot.customBooths.map { booth ->
            booth.copy(
                planned = keepFavorites && booth.planned,
                completed = false
            )
        }
        return TripSnapshot(exportedStates, exportedCustomBooths, snapshot.booths, snapshot.hallMeta)
    }

    // ---------- 解码 ----------

    fun decode(raw: String): TripSnapshot {
        if (raw.isBlank()) return TripSnapshot()
        ensureJsonShape(raw)
        val root = JSONObject(raw)
        val statesArray = root.optJSONArray("items") ?: JSONArray()
        val customBoothsArray = root.optJSONArray("customBooths") ?: JSONArray()
        val boothsArray = root.optJSONArray("booths") ?: JSONArray()
        // v5 才有 halls；v4 及更老的文件没有这个键，按空表处理，保持向后兼容
        val hallsArray = root.optJSONArray("halls") ?: JSONArray()
        if (maxOf(statesArray.length(), customBoothsArray.length(), boothsArray.length(), hallsArray.length()) > MAX_IMPORT_ITEMS) {
            error("import file has too many entries")
        }
        return normalize(
            TripSnapshot(
                states = parseStates(statesArray),
                customBooths = parseCustomBooths(customBoothsArray),
                booths = parseBooths(boothsArray),
                hallMeta = parseHalls(hallsArray)
            )
        )
    }

    private fun ensureJsonShape(raw: String) {
        var depth = 0
        var maxDepth = 0
        var inString = false
        var escaped = false
        for (char in raw) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > maxDepth) maxDepth = depth
                    }
                    '}', ']' -> depth -= 1
                }
            }
        }
        if (maxDepth > MAX_JSON_DEPTH) error("import file is nested too deeply")
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

    private fun parseHalls(items: JSONArray): Map<String, HallMeta> {
        val result = linkedMapOf<String, HallMeta>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = normalizePunctuation(item.optString("id")).trim().uppercase()
            if (id.isBlank()) continue
            val title = normalizePunctuation(item.optString("title", "")).trim().take(MAX_HALL_TITLE)
            val subtitle = normalizePunctuation(item.optString("subtitle", "")).trim().take(MAX_HALL_SUBTITLE)
            if (title.isBlank() && subtitle.isBlank()) {
                result.remove(id)
                continue
            }
            result[id] = HallMeta(title, subtitle)
        }
        return result
    }

    // ---------- 归一化 ----------

    fun normalize(snapshot: TripSnapshot): TripSnapshot {
        val normalizedStates = normalizeStateKeys(snapshot.states).toMutableMap()
        val normalizedCustomBooths = snapshot.customBooths.map { booth ->
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

        val normalizedHallMeta = snapshot.hallMeta
            .mapKeys { (id, _) -> normalizePunctuation(id).trim().uppercase() }
            .mapValues { (_, meta) ->
                HallMeta(
                    title = normalizePunctuation(meta.title).trim().take(MAX_HALL_TITLE),
                    subtitle = normalizePunctuation(meta.subtitle).trim().take(MAX_HALL_SUBTITLE)
                )
            }
            .filterValues { it.title.isNotBlank() || it.subtitle.isNotBlank() }

        return TripSnapshot(normalizedStates, normalizedCustomBooths, snapshot.booths, normalizedHallMeta)
    }

    private fun normalizeStateKeys(states: Map<String, BoothState>): Map<String, BoothState> {
        val result = mutableMapOf<String, BoothState>()
        states.forEach { (id, state) ->
            val normalizedId = normalizeStateId(id)
            if (normalizedId == id || normalizedId !in result) {
                result[normalizedId] = state.copy(
                    note = normalizePunctuation(state.note),
                    customName = state.customName?.let(::normalizePunctuation)
                )
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

    // ---------- 合并 ----------

    /**
     * 「保留已有数据」的合并语义：**永不减少收藏**。
     *
     * 旧实现直接 `previous.copy(planned = incoming.planned)`，导入一份没有收藏的文件
     * 就会把本地收藏清零，正是「导入后收藏消失」的根因。
     * 现在改成并集：本地已有的收藏一定保留，导入里带收藏的再补上。
     */
    fun mergeKeepExisting(previous: BoothState, incoming: BoothState): BoothState =
        previous.copy(
            planned = previous.planned || incoming.planned,
            planOrder = if (incoming.planned) minOf(previous.planOrder, incoming.planOrder) else previous.planOrder
        )

    /** 自定义展台同样不允许被导入清掉收藏。 */
    fun mergeKeepExisting(previous: CustomBooth, incoming: CustomBooth): CustomBooth =
        previous.copy(
            planned = previous.planned || incoming.planned,
            planOrder = if (incoming.planned) minOf(previous.planOrder, incoming.planOrder) else previous.planOrder
        )
}
