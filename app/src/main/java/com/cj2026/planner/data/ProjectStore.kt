package com.cj2026.planner.data

import android.content.Context
import com.cj2026.planner.model.ExpoProject
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("expo_projects", Context.MODE_PRIVATE)

    fun loadProjects(): List<ExpoProject> {
        val raw = prefs.getString("projects_json", null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val projects = root.optJSONArray("projects") ?: JSONArray()
            buildList {
                for (index in 0 until projects.length()) {
                    val item = projects.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val title = item.optString("title").trim()
                    if (id.isBlank() || title.isBlank()) continue
                    add(
                        ExpoProject(
                            id = id,
                            title = title,
                            subtitle = item.optString("subtitle", "").trim(),
                            year = item.optString("year", "").trim(),
                            note = item.optString("note", ""),
                            halls = emptyList(),
                            booths = emptyList()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveProjects(projects: List<ExpoProject>) {
        val projectsJson = JSONArray()
        projects.forEach { project ->
            projectsJson.put(
                JSONObject().apply {
                    put("id", project.id)
                    put("title", project.title)
                    put("subtitle", project.subtitle)
                    put("year", project.year)
                    put("note", project.note)
                    put("halls", JSONArray())
                    put("booths", JSONArray())
                }
            )
        }
        prefs.edit()
            .putString(
                "projects_json",
                JSONObject().apply {
                    put("version", 1)
                    put("projects", projectsJson)
                }.toString()
            )
            .apply()
    }
}
