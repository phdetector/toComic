package com.cj2026.planner.data

import android.content.Context
import java.util.UUID

class SuggestionStore(context: Context) {
    private val prefs = context.getSharedPreferences("suggestion_store", Context.MODE_PRIVATE)

    fun clientId(): String {
        prefs.getString("client_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            prefs.edit().putString("client_id", id).commit()
        }
    }

    fun clientSecret(): String = prefs.getString("client_secret", "").orEmpty()

    fun saveClientSecret(secret: String) {
        prefs.edit().putString("client_secret", secret).commit()
    }
}
