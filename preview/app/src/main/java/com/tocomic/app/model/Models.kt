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
    val planOrder: Int = Int.MAX_VALUE
)

data class CustomBooth(
    val id: String,
    val hallId: String,
    val boothCode: String,
    val name: String,
    val note: String = "",
    val planned: Boolean = false,
    val planOrder: Int = Int.MAX_VALUE
) {
    fun asBooth(): Booth = Booth(id, hallId, boothCode, name)
    fun asState(): BoothState = BoothState(note = note, planned = planned, planOrder = planOrder)
}

data class TripSnapshot(
    val states: Map<String, BoothState> = emptyMap(),
    val customBooths: List<CustomBooth> = emptyList()
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
