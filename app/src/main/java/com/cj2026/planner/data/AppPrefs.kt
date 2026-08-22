package com.cj2026.planner.data

import android.content.Context
import org.json.JSONArray

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var declarationAccepted: Boolean
        get() = prefs.getBoolean("declaration_accepted", false)
        set(value) = prefs.edit().putBoolean("declaration_accepted", value).apply()

    fun seenNoticeIds(): Set<String> {
        val raw = prefs.getString("seen_notice_ids", null) ?: return emptySet()
        return runCatching {
            val items = JSONArray(raw)
            buildSet {
                for (index in 0 until items.length()) {
                    val id = items.optString(index).trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun markNoticeSeen(id: String) {
        val next = seenNoticeIds() + id
        val items = JSONArray()
        next.forEach { items.put(it) }
        prefs.edit().putString("seen_notice_ids", items.toString()).apply()
    }

    var importConflictMode: String?
        get() = prefs.getString("import_conflict_mode", null)
        set(value) = prefs.edit().putString("import_conflict_mode", value).apply()
}
