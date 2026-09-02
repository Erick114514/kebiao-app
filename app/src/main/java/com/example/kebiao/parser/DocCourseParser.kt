package com.example.kebiao.parser

import com.example.kebiao.model.Course
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import com.example.kebiao.model.ScheduleParseResult

class DocCourseParser {

    fun parse(rawLines: List<String>): ScheduleParseResult {
        val rows = rawLines
            .map { raw -> raw.split('\t').map { it.trim() } }
            .filter { row -> row.any { it.isNotBlank() } }

        val courses = parseTransposed(rows)
            .ifEmpty { parseRowOriented(rows) }
        val deduped = courses.distinctBy {
            listOf(
                it.dayIndex,
                it.startPeriod,
                it.endPeriod,
                it.name,
                it.teacher,
                it.weeks,
                it.location
            )
        }
        val warnings = if (deduped.isEmpty()) {
            listOf("未识别到课程。请确认文档包含课程名称、星期和节次信息。")
        } else {
            emptyList()
        }
        return ScheduleParseResult(deduped, DEFAULT_PERIOD_TIMES, warnings)
    }

    private fun parseTransposed(rows: List<List<String>>): List<Course> {
        val headerIndex = rows.indexOfFirst { row -> dayColumns(row).size >= 2 }
        if (headerIndex < 0) return emptyList()

        val dayByColumn = dayColumns(rows[headerIndex])
        val courses = mutableListOf<Course>()
        for (rowIndex in headerIndex + 1 until rows.size) {
            val row = rows[rowIndex]
            val rowDay = singleDay(row.joinToString(" "))
            for ((columnIndex, cell) in row.withIndex()) {
                if (cell.isBlank()) continue
                val day = dayByColumn[columnIndex] ?: rowDay ?: continue
                courses += parseCellLines(cell, day)
            }
        }
        return courses
    }

    private fun parseCellLines(cell: String, day: Int): List<Course> {
        val whole = cell.replace(Regex("\\s+"), " ").trim()
        if (whole.isNotBlank()) {
            parseCourseLine(whole, day)?.let { return listOf(it) }
        }

        val lines = cell
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (extractPeriodsFromText(line) != null || blocks.isEmpty()) {
                blocks.add(mutableListOf())
            }
            blocks.last().add(line)
        }
        return blocks.mapNotNull { block ->
            parseCourseLine(block.joinToString(" "), day)
        }
    }

    private fun parseCourseLine(text: String, day: Int): Course? {
        val periods = extractPeriods(listOf(text), text) ?: return null
        val weeks = extractWeeks(listOf(text), text)
        val location = extractLocation(listOf(text), text)
        val teacher = extractTeacher(listOf(text), text)
        val name = extractName(listOf(text), text, teacher, location.orEmpty())
        if (name.isBlank()) return null
        return Course(
            name = normalizeCourseName(name),
            teacher = teacher,
            weeks = weeks.orEmpty(),
            location = location.orEmpty(),
            dayIndex = day,
            startPeriod = periods.first,
            endPeriod = periods.second
        )
    }

    private fun parseRowOriented(rows: List<List<String>>): List<Course> {
        val courses = mutableListOf<Course>()
        var lastDay: Int? = null

        for (row in rows) {
            val cells = row.filter { it.isNotBlank() }
            if (cells.isEmpty()) continue
            val text = cells.joinToString(" ")
            val days = findAllDays(text)
            val day = if (days.size == 1) days.first() else null
            if (day != null) lastDay = day
            val resolvedDay = day ?: lastDay ?: continue

            val periods = extractPeriods(cells, text) ?: continue
            val weeks = extractWeeks(cells, text)
            val location = extractLocation(cells, text)
            val teacher = extractTeacher(cells, text)
            val name = extractName(cells, text, teacher, location.orEmpty())
            if (name.isBlank()) continue

            courses += Course(
                name = normalizeCourseName(name),
                teacher = teacher,
                weeks = weeks.orEmpty(),
                location = location.orEmpty(),
                dayIndex = resolvedDay,
                startPeriod = periods.first,
                endPeriod = periods.second
            )
        }
        return courses
    }

    private fun extractPeriods(cells: List<String>, text: String): Pair<Int, Int>? {
        for (cell in cells) {
            extractPeriodsFromText(cell)?.let { return it }
        }
        return extractPeriodsFromText(text)
    }

    private fun extractPeriodsFromText(text: String): Pair<Int, Int>? {
        val range = PERIOD_RANGE_REGEX.find(text)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull()
            val end = range.groupValues[2].toIntOrNull()
            if (start != null && end != null) {
                return start.coerceAtLeast(1) to end.coerceAtLeast(start)
            }
        }
        val single = SINGLE_PERIOD_REGEX.find(text)
        if (single != null) {
            val period = single.groupValues[1].toIntOrNull()
            if (period != null) {
                return period.coerceAtLeast(1) to period.coerceAtLeast(1)
            }
        }
        return null
    }

    private fun extractWeeks(cells: List<String>, text: String): String? {
        return cells.firstNotNullOfOrNull { cell ->
            WEEKS_REGEX.find(cell)?.value?.trim()
        } ?: WEEKS_REGEX.find(text)?.value?.trim()
    }

    private fun extractLocation(cells: List<String>, text: String): String? {
        cells.firstNotNullOfOrNull { cell ->
            LOCATION_REGEX.find(cell)?.value?.trim()
        }?.let { return it }
        val match = LOCATION_REGEX.find(text) ?: return null
        val after = text.substring(match.range.last + 1)
        return if (after.isBlank()) {
            text.substring(match.range.first).trim()
        } else {
            match.value.trim()
        }
    }

    private fun extractTeacher(cells: List<String>, text: String): String {
        if (cells.size == 1) {
            extractStarTeachers(text).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        if (cells.size > 1) {
            cells.firstOrNull { it.contains('*') }?.let {
                return it.replace("*", "").trim()
            }
            val nameIndex = cells.indexOfFirst { !isMetaCell(it) && !isNumeric(it) }
            if (nameIndex >= 0) {
                cells.drop(nameIndex + 1).firstOrNull { isTeacherCandidate(it) }?.let {
                    return it.trim()
                }
            }
        }
        if (cells.size == 1) {
            val location = extractLocation(cells, text).orEmpty()
            val metaRemoved = text
                .replace(DAY_REGEX, " ")
                .replace(PERIOD_RANGE_REGEX, " ")
                .replace(SINGLE_PERIOD_REGEX, " ")
                .replace(WEEKS_REGEX, " ")
                .replace(Regex(Regex.escape(location)), " ")
                .replace(Regex("[\\d.,，、;；:：()（）%]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val candidates = metaRemoved
                .split(' ')
                .filter {
                    it.length in 2..8 && it.any { ch -> ch in '\u4e00'..'\u9fa5' }
                }
            if (candidates.size > 1) {
                candidates.lastOrNull()?.let { return it.trim() }
            }
        }
        return extractStarTeachers(text).joinToString(" ")
    }

    private fun extractName(
        cells: List<String>,
        text: String,
        teacher: String,
        location: String
    ): String {
        cells.firstOrNull { cell ->
            !cell.contains('*') && !isMetaCell(cell) && !isNumeric(cell) &&
                cell != teacher && cell != location
        }?.let { return cleanName(it) }

        var remaining = text
        remaining = remaining.replace(DAY_REGEX, " ")
        remaining = remaining.replace(PERIOD_RANGE_REGEX, " ")
        remaining = remaining.replace(SINGLE_PERIOD_REGEX, " ")
        remaining = remaining.replace(WEEKS_REGEX, " ")
        remaining = remaining.replace(STAR_TEACHER_REGEX, " ")
        if (teacher.isNotBlank()) {
            remaining = remaining.replace(Regex(Regex.escape(teacher) + "\\*?"), " ")
        }
        if (location.isNotBlank()) {
            remaining = remaining.replace(Regex(Regex.escape(location)), " ")
        }
        remaining = remaining.replace(Regex("[.,，、;；:：%]+"), " ")
        val tokens = remaining.split(' ').filter { it.isNotBlank() }
        val codeIndex = tokens.indexOfFirst {
            it.contains('_') || it.contains('＿')
        }
        if (codeIndex >= 0 && codeIndex < tokens.lastIndex) {
            remaining = tokens.subList(0, codeIndex + 1).joinToString(" ")
        }
        return cleanName(remaining.replace(Regex("\\s+"), " ").trim())
    }

    private fun cleanName(value: String): String {
        return value
            .trim()
            .replace(Regex("[_＿]+$"), "")
            .replace(Regex("^[_＿]+"), "")
            .removePrefix("课程名称")
            .removePrefix(":")
            .removePrefix("：")
            .trim()
    }

    private fun isTeacherCandidate(cell: String): Boolean {
        val value = cell.trim()
        if (value.length !in 2..8) return false
        if (!value.any { it in '\u4e00'..'\u9fa5' }) return false
        if (isNumeric(value) || isMetaCell(value)) return false
        return true
    }

    private fun isMetaCell(cell: String): Boolean {
        val value = cell.trim()
        if (value.isBlank()) return true
        if (value.contains('*')) return true
        if (isNumeric(value)) return true
        if (HEADER_TEXT.any { value.contains(it) }) return true
        if (singleDay(value) != null) return true
        if (extractPeriodsFromText(value) != null) return true
        if (WEEKS_REGEX.find(value) != null) return true
        if (isLocation(value)) return true
        return value.all { it in "：:,.，、;；-—－–~()（）" }
    }

    private fun isLocation(value: String): Boolean {
        return value.length >= 3 && LOCATION_KEYWORDS.any { value.contains(it) }
    }

    private fun isNumeric(value: String): Boolean {
        return value.matches(Regex("\\d+(?:\\.\\d+)?"))
    }

    private fun dayColumns(row: List<String>): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        for ((index, cell) in row.withIndex()) {
            singleDay(cell)?.let { result[index] = it }
        }
        return result
    }

    private fun singleDay(text: String): Int? {
        return findAllDays(text).singleOrNull()
    }

    private fun findAllDays(text: String): List<Int> {
        return DAY_REGEX.findAll(text).mapNotNull { match ->
            when (match.groupValues[1]) {
                "一" -> 0
                "二" -> 1
                "三" -> 2
                "四" -> 3
                "五" -> 4
                "六" -> 5
                "日" -> 6
                else -> null
            }
        }.toList()
    }

    private fun extractStarTeachers(text: String): List<String> {
        return STAR_TEACHER_REGEX.findAll(text).flatMap { match ->
            listOfNotNull(
                match.groupValues[1].takeIf { it.isNotBlank() },
                match.groupValues[2].takeIf { it.isNotBlank() }
            )
        }.toList()
    }

    private fun normalizeCourseName(name: String): String {
        val trimmed = name.trim().replace(Regex("\\s+"), " ")
        val hasExistingSection = Regex("[(（][一二三四五六七八九十]+[)）]").containsMatchIn(trimmed)
        if (hasExistingSection) {
            return trimmed
                .replace(Regex("(?:[-_＿]?|一?)\\d{2}$"), "")
                .replace(Regex("\\s+"), "")
                .trim()
        }
        val spaceMatch = COURSE_SECTION_SPACE_REGEX.find(trimmed)
        if (spaceMatch != null) {
            return "${spaceMatch.groupValues[1]}（一）${spaceMatch.groupValues[2]}"
        }
        val underscoreMatch = COURSE_SECTION_UNDERSCORE_REGEX.find(trimmed)
        if (underscoreMatch != null) {
            return "${underscoreMatch.groupValues[1]}（一）${underscoreMatch.groupValues[2]}"
        }
        val dashMatch = COURSE_SECTION_DASH_REGEX.find(trimmed)
        if (dashMatch != null) {
            return "${dashMatch.groupValues[1]}（一）${dashMatch.groupValues[2]}"
        }
        val match = COURSE_SECTION_REGEX.find(trimmed)
        return if (match != null) {
            "${match.groupValues[1]}（一）${match.groupValues[3]}"
        } else {
            trimmed
        }
    }

    companion object {
        private val DAY_REGEX = Regex("(?:星期|周)\\s*([一二三四五六日])")
        private val PERIOD_RANGE_REGEX =
            Regex("(?:第\\s*)?(\\d{1,2})\\s*[-—－–~至]\\s*(\\d{1,2})\\s*节")
        private val SINGLE_PERIOD_REGEX = Regex("第\\s*(\\d{1,2})\\s*节")
        private val WEEKS_REGEX = Regex(
            "(?:第\\s*)?\\d{1,2}\\s*[-—－–~至]\\s*\\d{1,2}\\s*周|" +
                "第\\s*\\d{1,2}\\s*周|单周|双周"
        )
        private val LOCATION_REGEX =
            Regex("\\S*(?:校区|楼|教室|实验室|中心|场|室)\\S*(?:\\s+\\S*){0,2}")
        private val LOCATION_KEYWORDS = listOf("校区", "楼", "教室", "实验室", "中心", "场", "室")
        private val HEADER_TEXT = listOf(
            "课程名称",
            "课程代码",
            "教师",
            "上课时间",
            "上课地点",
            "周次",
            "教室",
            "学分",
            "序号",
            "节次"
        )
        private val COURSE_SECTION_REGEX = Regex("^(.+?)(一)(\\d{2})$")
        private val COURSE_SECTION_UNDERSCORE_REGEX = Regex("^(.+?)[_＿](\\d{2})$")
        private val COURSE_SECTION_DASH_REGEX = Regex("^(.+?)[-－](\\d{2})$")
        private val COURSE_SECTION_SPACE_REGEX = Regex("^(.+?)\\s+(\\d{2})$")
        private val STAR_TEACHER_REGEX =
            Regex("([\\u4e00-\\u9fa5·]{2,8})\\*([\\u4e00-\\u9fa5·]{2,8})?")
        private const val CHINESE_RANGE = "\\u4e00-\\u9fa5"
    }
}
