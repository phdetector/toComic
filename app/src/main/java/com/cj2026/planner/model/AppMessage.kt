package com.cj2026.planner.model

data class AppNotice(
    val id: String,
    val title: String,
    val body: String
)

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val body: String,
    val url: String
)

data class AppMessages(
    val notices: List<AppNotice> = emptyList(),
    val update: AppUpdate? = null
)
