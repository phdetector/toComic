package com.cj2026.planner.model

data class ExpoProject(
    val id: String,
    val title: String,
    val subtitle: String,
    val year: String,
    val note: String = "",
    val halls: List<Hall>,
    val booths: List<Booth>
)
