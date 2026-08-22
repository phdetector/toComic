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
    const val ENDPOINT = "http://101.37.21.166/api/app/messages"

    suspend fun fetch(): Result<AppMessages> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) error("message server returned $code $body")

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
                    AppUpdate(
                        versionCode = item.optInt("versionCode"),
                        versionName = item.optString("versionName"),
                        title = item.optString("title"),
                        body = item.optString("body"),
                        url = item.optString("url")
                    )
                }
                AppMessages(notices = notices.filter { it.id.isNotBlank() }, update = update)
            } finally {
                connection.disconnect()
            }
        }
    }
}
