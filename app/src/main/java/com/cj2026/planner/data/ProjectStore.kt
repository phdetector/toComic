package com.cj2026.planner.data

import android.content.Context
import com.cj2026.planner.model.ExpoProject
import com.cj2026.planner.model.ProjectOverrides
import org.json.JSONArray
import org.json.JSONObject

data class ProjectState(
    val customProjects: List<ExpoProject> = emptyList(),
    val deletedBuiltInIds: Set<String> = emptySet(),
    val overrides: Map<String, ProjectOverrides> = emptyMap()
)

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("expo_projects", Context.MODE_PRIVATE)

    fun loadProjectState(): ProjectState {
        val raw = prefs.getString("projects_json", null) ?: return ProjectState()
        return runCatching {
            val root = JSONObject(raw)
            val version = root.optInt("version", 1)
            val projectsArray = if (version >= 2) {
                root.optJSONArray("customProjects") ?: JSONArray()
            } else {
                root.optJSONArray("projects") ?: JSONArray()
            }
            ProjectState(
                customProjects = parseProjects(projectsArray),
                deletedBuiltInIds = parseStringSet(root.optJSONArray("deletedBuiltInIds")),
                overrides = parseOverrides(root.optJSONObject("builtInOverrides"))
            )
        }.getOrDefault(ProjectState())
    }

    fun saveProjects(
        customProjects: List<ExpoProject>,
        deletedBuiltInIds: Set<String>,
        overrides: Map<String, ProjectOverrides>
    ) {
        val customJson = JSONArray()
        customProjects.forEach { project -> customJson.put(project.toJson()) }

        val deletedJson = JSONArray()
        deletedBuiltInIds.sorted().forEach { deletedJson.put(it) }

        val overridesJson = JSONObject()
        overrides.toSortedMap().forEach { (id, override) ->
            overridesJson.put(
                id,
                JSONObject().apply {
                    put("title", override.title)
                    put("subtitle", override.subtitle)
                    put("year", override.year)
                    put("note", override.note)
                }
            )
        }

        prefs.edit()
            .putString(
                "projects_json",
                JSONObject().apply {
                    put("version", 2)
                    put("customProjects", customJson)
                    put("deletedBuiltInIds", deletedJson)
                    put("builtInOverrides", overridesJson)
                }.toString()
            )
            .apply()
    }

    private fun parseProjects(projects: JSONArray): List<ExpoProject> = buildList {
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

    private fun parseStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun parseOverrides(root: JSONObject?): Map<String, ProjectOverrides> {
        if (root == null) return emptyMap()
        return buildMap {
            root.keys().forEach { id ->
                val item = root.optJSONObject(id) ?: return@forEach
                put(
                    id,
                    ProjectOverrides(
                        title = item.optString("title", "").trim(),
                        subtitle = item.optString("subtitle", "").trim(),
                        year = item.optString("year", "").trim(),
                        note = item.optString("note", "")
                    )
                )
            }
        }
    }
}

private fun ExpoProject.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("subtitle", subtitle)
    put("year", year)
    put("note", note)
    put("halls", JSONArray())
    put("booths", JSONArray())
}
