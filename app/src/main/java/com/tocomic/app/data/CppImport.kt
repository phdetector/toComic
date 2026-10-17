package com.tocomic.app.data

import com.tocomic.app.model.CustomBooth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 CPP（无差别同人站 allcpp.cn）链接导入展品信息。
 *
 * 依据 `icp.red_qkSBTvItA_提取技术报告.md`：
 * - `icp.red/{code}` 是短链，HTTP 302 跳到 `https://www.allcpp.cn/d/{id}.do`（必须自己读 Location，不能让 HTTP 库跟随）
 * - 标题取 `GET /allcpp/djs/detail.do?doujinshiID={id}`（**大写 ID**，小写会 400）的 `result.name`
 * - 摊位号取 `GET /allcpp/djs/joinedEvent.do?doujinshiid={id}&isnew={0,1,2}`，
 *   `isnew` 必需，三档都要遍历后按 (eventId, position) 去重
 *
 * 展馆归属：**摊位号前缀**做展馆（`叁A11叁A12` -> 展馆「叁」），摊位号整体做展台号；
 * 没有参与记录、摊位号为空或为「摊位未公布」这类占位串 -> 默认展馆。
 * 一个展品只取一条参与记录，所以永远只属于一个展馆。
 */
object CppImport {
    const val DEFAULT_HALL_ID = "DEFAULT"
    const val DEFAULT_HALL_SUBTITLE = "默认展馆"

    private const val USER_AGENT = "toComic/0.1 (Android)"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    private val SHORT_LINK = Regex("^https?://icp\\.red/([A-Za-z0-9]{4,16})/?$")
    private val EXHIBIT_PAGE = Regex("allcpp\\.cn/d/(\\d+)\\.do")
    private val NOT_ANNOUNCED = listOf("未公布", "待定", "暂无", "无摊位", "未分配")

    // ---------- 纯函数（可单测） ----------

    fun isShortLink(url: String): Boolean = SHORT_LINK.matches(url.trim())

    fun exhibitIdFromUrl(url: String): String? = EXHIBIT_PAGE.find(url)?.groupValues?.getOrNull(1)

    /** 摊位号是否「已公布」。空值与「摊位未公布」这类占位串都算没有摊位号。 */
    fun positionIsAnnounced(position: String): Boolean {
        val value = position.trim()
        if (value.isEmpty()) return false
        return NOT_ANNOUNCED.none { value.contains(it) }
    }

    /**
     * 摊位号前缀 -> 展馆号。取开头连续的 CJK 字符（非 ASCII、非数字），
     * 所以 `叁A11叁A12` / `叁I48` / `叁L01` 都得到「叁」；纯 ASCII 摊位号（如 `A11`）得 null。
     */
    fun hallIdFromPosition(position: String): String? {
        if (!positionIsAnnounced(position)) return null
        val prefix = position.trim().takeWhile { it.code > 0x7F && !it.isDigit() }.trim()
        return prefix.ifBlank { null }
    }

    /**
     * 一个展品可能有多条参与记录（跨多届展会，见对照样本 281927 的 5 条）。
     * 只取一条：优先「未结束且摊位号已公布」的，其次摊位号已公布的，最后按入场日期取最新。
     * 这保证一个展品永远只归属一个展馆。
     */
    fun chooseEvent(events: List<CppEvent>): CppEvent? {
        if (events.isEmpty()) return null
        val announced = events.filter { positionIsAnnounced(it.position) }
        if (announced.isNotEmpty()) {
            return announced.sortedWith(
                compareBy<CppEvent> { it.isEnded }.thenByDescending { it.enterTime }
            ).first()
        }
        return events.sortedByDescending { it.enterTime }.first()
    }

    fun parseDetailTitle(body: String): String? = runCatching {
        val root = JSONObject(body)
        val result = root.optJSONObject("result") ?: return null
        result.optString("name").trim().ifBlank { null }
    }.getOrNull()

    fun parseJoinedEvents(body: String): List<CppEvent> = runCatching {
        val root = JSONObject(body)
        val array = root.optJSONArray("result") ?: JSONArray()
        val out = mutableListOf<CppEvent>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            out += CppEvent(
                eventId = item.optString("eventId"),
                position = item.optString("position", "").trim(),
                eventName = item.optString("eventName", "").trim(),
                circleName = item.optString("circleName", "").trim(),
                enterTime = item.optString("enterTime", "").trim(),
                isEnded = item.optInt("isend", 0) == 1
            )
        }
        out
    }.getOrDefault(emptyList())

    /** 三档 isnew 的结果按 (eventId, position) 去重合并。 */
    fun dedupeEvents(events: List<CppEvent>): List<CppEvent> {
        val seen = HashSet<String>()
        return events.filter { seen.add("${it.eventId}|${it.position}") }
    }

    /**
     * 展品 -> 自定义展台。name 是展品标题，boothCode 是摊位号；
     * 没有摊位号时用展品 ID 当展台号（否则空展台号会在归一化时被丢弃）。
     */
    fun toCustomBooth(exhibit: CppExhibit): CustomBooth {
        val chosen = chooseEvent(exhibit.events)
        val position = chosen?.position.orEmpty()
        val announced = positionIsAnnounced(position)
        val hallId = if (announced) hallIdFromPosition(position) ?: DEFAULT_HALL_ID else DEFAULT_HALL_ID
        val boothCode = if (announced) position else exhibit.exhibitId
        val noteLines = buildList {
            if (exhibit.title.isBlank()) add("CPP 展品 $exhibit.exhibitId")
            chosen?.eventName?.takeIf { it.isNotBlank() }?.let { add("活动: $it") }
            chosen?.circleName?.takeIf { it.isNotBlank() }?.let { add("社团: $it") }
            chosen?.enterTime?.takeIf { it.isNotBlank() }?.let { add("入场: $it") }
            if (!announced && chosen != null) add("摊位号: 未公布")
            if (chosen == null) add("暂无参展记录")
            add("来源: https://www.allcpp.cn/d/${exhibit.exhibitId}.do")
        }
        return CustomBooth(
            id = "",
            hallId = hallId,
            boothCode = boothCode,
            name = exhibit.title.ifBlank { "CPP-${exhibit.exhibitId}" },
            note = noteLines.joinToString("\n").take(500)
        )
    }

    // ---------- 网络 ----------

    suspend fun fetchExhibit(link: String): Result<CppExhibit> = withContext(Dispatchers.IO) {
        runCatching {
            val input = link.trim()
            require(input.isNotEmpty()) { "链接为空" }
            val resolved = if (isShortLink(input)) {
                resolveShortLink(input) ?: throw IllegalStateException("短链无法解析")
            } else {
                input
            }
            val id = exhibitIdFromUrl(resolved)
                ?: throw IllegalStateException("不是 CPP 展品链接: $resolved")
            val title = parseDetailTitle(getBody("$DETAIL_API$id")).orEmpty()
            val events = mutableListOf<CppEvent>()
            for (isnew in 0..2) {
                runCatching { getBody("$JOINED_API$id&isnew=$isnew") }
                    .onSuccess { events += parseJoinedEvents(it) }
            }
            CppExhibit(exhibitId = id, title = title, events = dedupeEvents(events))
        }
    }

    private const val DETAIL_API = "https://www.allcpp.cn/allcpp/djs/detail.do?doujinshiID="
    private const val JOINED_API = "https://www.allcpp.cn/allcpp/djs/joinedEvent.do?doujinshiid="

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
        }

    private fun resolveShortLink(link: String): String? {
        val connection = open(link, followRedirects = false)
        return try {
            val code = connection.responseCode
            if (code !in 300..399) null else connection.getHeaderField("Location")
        } finally {
            connection.disconnect()
        }
    }

    private fun getBody(url: String): String {
        val connection = open(url, followRedirects = true)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("服务器返回 $code")
            val stream = connection.inputStream
            stream.use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) error("response too large")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}

data class CppEvent(
    val eventId: String,
    val position: String,
    val eventName: String,
    val circleName: String,
    val enterTime: String,
    val isEnded: Boolean
)

data class CppExhibit(
    val exhibitId: String,
    val title: String,
    val events: List<CppEvent>
) {
    val chosenEvent: CppEvent? get() = CppImport.chooseEvent(events)
    val hallId: String
        get() {
            val position = chosenEvent?.position.orEmpty()
            return if (CppImport.positionIsAnnounced(position)) {
                CppImport.hallIdFromPosition(position) ?: CppImport.DEFAULT_HALL_ID
            } else {
                CppImport.DEFAULT_HALL_ID
            }
        }
    val boothCode: String
        get() {
            val position = chosenEvent?.position.orEmpty()
            return if (CppImport.positionIsAnnounced(position)) position else exhibitId
        }
}
