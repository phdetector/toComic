package com.cj2026.planner.data

import com.cj2026.planner.model.ExpoProject

object ProjectData {
    val projects = listOf(
        ExpoProject(
            id = "chinajoy-2026",
            title = "ChinaJoy 2026",
            subtitle = "上海新国际博览中心",
            year = "2026",
            halls = ChinaJoyData.halls,
            booths = ChinaJoyData.booths
        )
    )

    fun byId(id: String): ExpoProject? = projects.firstOrNull { it.id == id }
}
