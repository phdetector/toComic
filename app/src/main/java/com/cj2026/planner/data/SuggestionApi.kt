package com.cj2026.planner.data

import com.cj2026.planner.model.Suggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SuggestionApi {
    const val ENDPOINT = "https://269278.xyz/api/suggestions"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    private fun readLimited(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        stream ?: return ""
        return stream.use { input ->
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
    }

    const val SIGNATURE_WINDOW_MS = 5 * 60 * 1000L

    private fun signature(clientId: String, secret: String, timestamp: Long): String {
        if (secret.isBlank()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$clientId:$timestamp"
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun submit(
        clientId: String,
        title: String,
        content: String,
        appVersion: String,
        deviceModel: String,
        androidVersion: String
    ): Result<String?> = withContext(Dispatchers.IO) {
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

                val body = readLimited(connection)
                val code = connection.responseCode
                if (code !in 200..299) error("suggestion server returned $code")
                if (!JSONObject(body).optBoolean("ok")) error("suggestion server rejected request")
                JSONObject(body).optString("secret").takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun list(clientId: String, secret: String): Result<List<Suggestion>> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val query = URLEncoder.encode(clientId, Charsets.UTF_8.name())
            val sig = URLEncoder.encode(signature(clientId, secret, timestamp), Charsets.UTF_8.name())
            val connection = URL("$ENDPOINT?clientId=$query&sig=$sig&ts=$timestamp").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val body = readLimited(connection)
                val code = connection.responseCode
                if (code !in 200..299) error("suggestion server returned $code")

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

    suspend fun rotate(clientId: String, secret: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val connection = URL("$ENDPOINT/rotate").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("clientId", clientId)
                    put("ts", timestamp)
                    put("sig", signature(clientId, secret, timestamp))
                }.toString()

                connection.outputStream.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                }

                val body = readLimited(connection)
                val code = connection.responseCode
                if (code !in 200..299) error("suggestion server returned $code")
                JSONObject(body).optString("secret").takeIf { it.isNotBlank() } ?: error("rotate response missing secret")
            } finally {
                connection.disconnect()
            }
        }
    }
}
