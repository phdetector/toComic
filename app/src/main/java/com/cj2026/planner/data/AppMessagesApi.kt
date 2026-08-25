package com.cj2026.planner.data

import com.cj2026.planner.model.AppMessages
import com.cj2026.planner.model.AppNotice
import com.cj2026.planner.model.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppMessagesApi {
    const val ENDPOINT = "https://269278.xyz/api/app/messages"

    private val ALLOWED_UPDATE_HOSTS = setOf("269278.xyz", "www.269278.xyz")

    private fun isAllowedUpdateUrl(value: String): Boolean {
        if (!value.startsWith("https://")) return false
        return runCatching {
            val host = java.net.URI(value).host?.lowercase()
            host != null && host in ALLOWED_UPDATE_HOSTS
        }.getOrDefault(false)
    }

    suspend fun fetch(): Result<AppMessages> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val code = connection.responseCode
                val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
                val body = stream?.use { input ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 1024 * 1024) error("response too large")
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
                if (code !in 200..299) error("message server returned $code")

                val root = JSONObject(body)
                val notices = buildList {
                    val items = root.optJSONArray("notices") ?: return@buildList
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        add(
                            AppNotice(
                                id = item.optString("id"),
                                title = item.optString("title"),
                                body = item.optString("body")
                            )
                        )
                    }
                }
                val update = root.optJSONObject("update")?.takeUnless { it == JSONObject.NULL }?.let { item ->
                    val versionCode = item.optInt("versionCode")
                    if (versionCode !in 1..99_999) {
                        null
                    } else {
                        AppUpdate(
                            versionCode = versionCode,
                            versionName = item.optString("versionName").take(40),
                            title = item.optString("title").take(200),
                            body = item.optString("body").take(2000),
                            url = item.optString("url").takeIf(::isAllowedUpdateUrl).orEmpty()
                        )
                    }
                }
                AppMessages(notices = notices.filter { it.id.isNotBlank() }, update = update)
            } finally {
                connection.disconnect()
            }
        }
    }
}
