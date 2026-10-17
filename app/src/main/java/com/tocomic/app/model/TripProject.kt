package com.tocomic.app.model

data class TripProject(
    val id: String,
    val title: String,
    val subtitle: String,
    val year: String,
    val note: String = "",
    val halls: List<Hall>,
    val booths: List<Booth>
)

data class ProjectOverrides(
    val title: String,
    val subtitle: String,
    val year: String,
    val note: String
)
