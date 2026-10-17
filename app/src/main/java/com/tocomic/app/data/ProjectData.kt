package com.tocomic.app.data

import com.tocomic.app.model.TripProject

object ProjectData {
    val projects = listOf(
        TripProject(
            id = "chinajoy-2026",
            title = "ChinaJoy 2026",
            subtitle = "上海新国际博览中心",
            year = "2026",
            halls = ChinaJoyData.halls,
            booths = ChinaJoyData.booths
        )
    )

    fun byId(id: String): TripProject? = projects.firstOrNull { it.id == id }
}
