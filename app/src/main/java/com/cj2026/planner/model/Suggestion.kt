package com.cj2026.planner.model

data class Suggestion(
    val id: String,
    val title: String,
    val content: String,
    val reply: String = "",
    val submittedAt: Long = 0L,
    val repliedAt: Long? = null
)
