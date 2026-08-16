package com.cj2026.planner.data

import com.cj2026.planner.model.Suggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SuggestionApi {
    const val ENDPOINT = "http://101.37.21.166/api/suggestions"

    suspend fun submit(
        clientId: String,
        title: String,
        content: String,
        appVersion: String,
        deviceModel: String,
        androidVersion: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("clientId", clientId)
                    put("title", title)
                    put("content", content)
                    put("appVersion", appVersion)
                    put("deviceModel", deviceModel)
                    put("androidVersion", androidVersion)
                    put("submittedAt", System.currentTimeMillis())
                }.toString()

                connection.outputStream.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) error("suggestion server returned $code $body")
                if (!JSONObject(body).optBoolean("ok")) error("suggestion server rejected request")
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun list(clientId: String): Result<List<Suggestion>> = withContext(Dispatchers.IO) {
        runCatching {
            val query = URLEncoder.encode(clientId, Charsets.UTF_8.name())
            val connection = URL("$ENDPOINT?clientId=$query").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) error("suggestion server returned $code $body")

                val root = JSONObject(body)
                if (!root.optBoolean("ok")) error("suggestion server rejected request")
                val items = root.optJSONArray("suggestions") ?: error("suggestion list missing")
                buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        add(
                            Suggestion(
                                id = item.optString("id"),
                                title = item.optString("title"),
                                content = item.optString("content"),
                                reply = item.optString("reply"),
                                submittedAt = item.optLong("submittedAt"),
                                repliedAt = if (item.isNull("repliedAt")) null else item.optLong("repliedAt")
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
