package com.tocomic.app.model

data class Hall(
    val id: String,
    val title: String,
    val subtitle: String,
    val accent: Long
)

data class Booth(
    val id: String,
    val hallId: String,
    val boothCode: String,
    val name: String,
    val logoUrl: String? = null
)

data class BoothState(
    val note: String = "",
    val customName: String? = null,
    val planned: Boolean = false,
    val planOrder: Int = Int.MAX_VALUE,
    val startTime: String = "",
    val endTime: String = "",
    val completed: Boolean = false
)

data class CustomBooth(
    val id: String,
    val hallId: String,
    val boothCode: String,
    val name: String,
    val note: String = "",
    val planned: Boolean = false,
    val planOrder: Int = Int.MAX_VALUE,
    val startTime: String = "",
    val endTime: String = "",
    val completed: Boolean = false
) {
    fun asBooth(): Booth = Booth(id, hallId, boothCode, name)
    fun asState(): BoothState = BoothState(
        note = note,
        planned = planned,
        planOrder = planOrder,
        startTime = startTime,
        endTime = endTime,
        completed = completed
    )
}

data class TripSnapshot(
    val states: Map<String, BoothState> = emptyMap(),
    val customBooths: List<CustomBooth> = emptyList(),
    val booths: List<Booth> = emptyList()
)

data class BoothUi(
    val booth: Booth,
    val state: BoothState
) {
    val displayName: String = state.customName?.takeIf { it.isNotBlank() } ?: booth.name
    val displayCode: String = "${booth.hallId}-${booth.boothCode}"
}

enum class TripTab(val label: String) {
    Plan("计划"), Overview("总览")
}

enum class SortMode {
    Default, Completion, Time
}
