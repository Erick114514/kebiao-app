package com.example.kebiao.model

data class Course(
    val name: String,
    val teacher: String,
    val weeks: String,
    val location: String,
    val dayIndex: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val rawLines: List<String> = emptyList()
) {
    val displayName: String
        get() = name.ifBlank { "未识别课程" }
}

data class ScheduleParseResult(
    val courses: List<Course>,
    val periodTimes: List<String>,
    val warnings: List<String>
)
