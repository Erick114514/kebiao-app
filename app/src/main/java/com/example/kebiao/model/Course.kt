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

val DEFAULT_PERIOD_TIMES = listOf(
    "第1节\n08:00-08:45",
    "第2节\n08:50-09:35",
    "第3节\n09:50-10:35",
    "第4节\n10:40-11:25",
    "第5节\n11:30-12:15",
    "第6节\n14:00-14:45",
    "第7节\n14:50-15:35",
    "第8节\n15:50-16:35",
    "第9节\n16:40-17:25",
    "第10节\n17:30-18:15",
    "第11节\n19:00-19:45",
    "第12节\n19:50-20:35",
    "第13节\n20:45-21:30"
)
