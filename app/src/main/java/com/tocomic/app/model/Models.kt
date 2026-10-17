package com.tocomic.app.model

data class Hall(
    val id: String,
    val title: String,
    val subtitle: String,
    val accent: Long
)

// 用户对展馆显示名称/备注的自定义。title/subtitle 都允许为空，
// 为空时回落到展馆号与默认副标题。
data class HallMeta(
    val title: String = "",
    val subtitle: String = ""
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

// 导出文件里携带的项目信息，用于「在主页导入完整项目」时还原名称。
data class ProjectSpec(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val year: String = "",
    val note: String = ""
)

data class TripSnapshot(
    val states: Map<String, BoothState> = emptyMap(),
    val customBooths: List<CustomBooth> = emptyList(),
    val booths: List<Booth> = emptyList(),
    val hallMeta: Map<String, HallMeta> = emptyMap(),
    val project: ProjectSpec? = null
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
