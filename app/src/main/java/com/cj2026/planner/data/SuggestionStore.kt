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

    // Recovery flow: when the local secret is lost there is no way to prove
    // ownership of the old clientId (server never re-issues it), so we start a
    // fresh identity. Old suggestion history becomes unreadable by design.
    fun resetIdentity(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("client_id", id).remove("client_secret").commit()
        return id
    }
}
