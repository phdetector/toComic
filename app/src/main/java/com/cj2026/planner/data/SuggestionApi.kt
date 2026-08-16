package com.cj2026.planner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SuggestionApi {
    const val ENDPOINT = "http://101.37.21.166/api/suggestions"

    suspend fun submit(
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
            } finally {
                connection.disconnect()
            }
        }
    }
}
