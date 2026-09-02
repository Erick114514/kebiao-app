package com.example.kebiao.parser

import com.example.kebiao.model.Course
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import com.example.kebiao.model.ScheduleParseResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrElement(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class OcrLine(
    val text: String,
    val elements: List<OcrElement>
) {
    val left: Float get() = elements.minOfOrNull { it.left } ?: 0f
    val top: Float get() = elements.minOfOrNull { it.top } ?: 0f
    val right: Float get() = elements.maxOfOrNull { it.right } ?: 0f
    val bottom: Float get() = elements.maxOfOrNull { it.bottom } ?: 0f
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

class PdfCourseParser {

    private data class DayColumn(val dayIndex: Int, val left: Float, val right: Float)

    private data class Fragment(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val startsColumn: Boolean = false
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private class EntryBuilder(val anchorX: Float, val firstY: Float) {
        val nameParts = mutableListOf<String>()
        val teacherParts = mutableListOf<String>()
        var weeks: String? = null
        var periods: String? = null
        var location: String? = null
        var teacherMode = false
        var lastTeacherY: Float? = null
    }

    fun parse(lines: List<OcrLine>, pageWidth: Float, pageHeight: Float): ScheduleParseResult {
        val cleanLines = lines
            .filter { it.elements.isNotEmpty() }
            .map { OcrLine(it.text.replace("\n", " ").trim(), it.elements) }

        val columns = detectColumns(cleanLines, pageWidth, pageHeight)
        val periodRows = detectPeriodRows(cleanLines, columns, pageHeight)
        val periodTimes = buildPeriodTimes(cleanLines, periodRows)

        val dayFragments = Array(7) { mutableListOf<Fragment>() }
        for (line in cleanLines) {
            if (isDayHeader(line) || isPeriodLabel(line)) continue
            val fragments = splitRepeated(line)
            for (fragment in fragments) {
                val day = columns.indexOfFirst { fragment.centerX in it.left..it.right }
                if (day >= 0) dayFragments[day].add(fragment)
            }
        }

        val courses = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        for (day in 0 until 7) {
            val blocks = clusterBlocks(dayFragments[day], pageHeight)
            for (block in blocks) {
                val parsed = parseBlock(block, day, periodRows)
                if (parsed.isEmpty()) {
                    warnings.add("未能解析课程块：${block.firstOrNull()?.text}")
                } else {
                    courses.addAll(parsed)
                }
            }
        }

        if (courses.isEmpty()) {
            warnings.add("未识别到课程。请确认导入的是选课结果课表 PDF，并尽量保持页面清晰。")
        }
        return ScheduleParseResult(courses.distinctBy {
            listOf(it.dayIndex, it.startPeriod, it.endPeriod, it.name, it.teacher, it.weeks, it.location)
        }, periodTimes, warnings)
    }

    private fun detectColumns(
        lines: List<OcrLine>,
        pageWidth: Float,
        pageHeight: Float
    ): List<DayColumn> {
        val found = mutableListOf<Pair<Int, OcrLine>>()
        for (line in lines) {
            val match = DAY_HEADER_REGEX.matchEntire(line.text.trim())
            if (match != null && line.top < pageHeight * 0.08f) {
                val day = when (match.groupValues[1]) {
                    "一" -> 0; "二" -> 1; "三" -> 2; "四" -> 3; "五" -> 4; "六" -> 5; "日" -> 6
                    else -> continue
                }
                found.add(day to line)
            }
        }
        val unique = found.distinctBy { it.first }.sortedBy { it.second.left }
        if (unique.size >= 3) {
            return unique.mapIndexed { index, pair ->
                val leftBound = if (index == 0) max(0f, pair.second.left - 48f) else pair.second.left - 24f
                val rightBound = if (index == unique.size - 1) pageWidth else unique[index + 1].second.left - 10f
                DayColumn(pair.first, leftBound, rightBound)
            }.sortedBy { it.dayIndex }
        }

        val ignoredLabels = setOf("上", "午", "下", "节次/时间")
        val fallbackLeft = lines
            .filter { !isPeriodLabel(it) && it.text.trim() !in ignoredLabels && it.right - it.left > 30f }
            .minOfOrNull { it.left } ?: (pageWidth * 0.18f)
        val tableLeft = fallbackLeft - 10f
        val colWidth = (pageWidth - tableLeft) / 7f
        return (0 until 7).map { i ->
            DayColumn(i, tableLeft + i * colWidth, tableLeft + (i + 1) * colWidth)
        }
    }

    private fun detectPeriodRows(
        lines: List<OcrLine>,
        columns: List<DayColumn>,
        pageHeight: Float
    ): Map<Int, Float> {
        val firstDayLeft = columns.minOfOrNull { it.left } ?: pageWidthFallback(lines)
        val rows = mutableMapOf<Int, Float>()
        for (line in lines) {
            val match = PERIOD_LABEL_REGEX.find(line.text)
            if (match != null && line.centerX < firstDayLeft) {
                val index = match.groupValues[1].toIntOrNull() ?: continue
                rows[index] = line.centerY
            }
        }
        if (rows.isEmpty()) {
            for (i in 1..13) {
                rows[i] = pageHeight * (i - 0.5f) / 13f
            }
        }
        return rows
    }

    private fun pageWidthFallback(lines: List<OcrLine>): Float {
        return lines.maxOfOrNull { it.right } ?: 2000f
    }

    private fun buildPeriodTimes(
        lines: List<OcrLine>,
        periodRows: Map<Int, Float>
    ): List<String> {
        val maxIndex = periodRows.keys.maxOrNull() ?: 0
        val result = MutableList(maxIndex) { "第${it + 1}节" }
        val times = mutableMapOf<Int, String>()
        for (line in lines) {
            val match = PERIOD_LABEL_REGEX.find(line.text)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull() ?: continue
                val timeMatch = Regex("[（(](.+?)[）)]").find(line.text)
                val rawTime = timeMatch?.groupValues?.get(1)
                    ?.replace("：", ":")
                    ?.replace("·", "-")
                    ?.replace(" ", "")
                    ?.trim()
                    ?: ""
                times[index] = if (rawTime.matches(TIME_PATTERN)) {
                    rawTime
                } else {
                    DEFAULT_PERIOD_TIMES.getOrNull(index - 1)?.substringAfter('\n') ?: rawTime
                }
            }
        }
        for ((index, time) in times) {
            if (index in 1..maxIndex) {
                result[index - 1] = if (time.isBlank()) "第${index}节" else "第${index}节\n$time"
            }
        }
        return result
    }

    private fun clusterBlocks(fragments: List<Fragment>, pageHeight: Float): List<List<Fragment>> {
        val sorted = fragments.sortedBy { it.centerY }
        val blocks = mutableListOf<MutableList<Fragment>>()
        for (fragment in sorted) {
            val last = blocks.lastOrNull()?.lastOrNull()
            if (last == null || fragment.centerY - last.centerY > pageHeight * 0.055f) {
                blocks.add(mutableListOf(fragment))
            } else {
                blocks.last().add(fragment)
            }
        }
        return blocks
    }

    private fun parseBlock(
        block: List<Fragment>,
        dayIndex: Int,
        periodRows: Map<Int, Float>
    ): List<Course> {
        val sorted = block.sortedBy { it.centerY }
        val result = mutableListOf<Course>()
        val builders = mutableListOf<EntryBuilder>()

        for (fragment in sorted) {
            val normalized = normalize(fragment.text)
            val nearestBefore = builders.minByOrNull { abs(fragment.left - it.anchorX) }
            val isMeta = isWeeks(normalized) || isPeriods(normalized) || isLocation(normalized)
            val teacherGap = nearestBefore?.lastTeacherY?.let { fragment.centerY - it } ?: Float.MAX_VALUE
            val isTeacherLike = normalized.contains('*') ||
                (nearestBefore?.teacherMode == true && teacherGap < 70f)
            if (!isMeta && !isTeacherLike &&
                builders.isNotEmpty() && builders.any { it.location != null }
            ) {
                result += finalizeBuilders(builders, dayIndex, periodRows)
                builders.clear()
            }

            val existing = builders.minByOrNull { abs(fragment.left - it.anchorX) }

            when {
                isWeeks(normalized) -> existing?.weeks = fragment.text
                isPeriods(normalized) -> existing?.periods = fragment.text
                isLocation(normalized) -> {
                    val builder = existing
                    if (builder != null) {
                        val currentLocation = builder.location
                        builder.location = if (currentLocation.isNullOrBlank()) {
                            fragment.text.trim()
                        } else {
                            currentLocation.trimEnd() + fragment.text.trim()
                        }
                    }
                }
                isTeacherLike -> {
                    val builder = existing ?: EntryBuilder(fragment.left, fragment.centerY).also { builders.add(it) }
                    builder.teacherMode = true
                    builder.lastTeacherY = fragment.centerY
                    builder.teacherParts.add(fragment.text.trim())
                }
                else -> {
                    val isCode = normalized.matches(Regex("\\d{1,3}"))
                    val farFromExisting = existing == null ||
                        abs(fragment.left - existing.anchorX) > max(96f, fragment.right * 0.04f)
                    if (existing == null || (farFromExisting && fragment.startsColumn && !isCode)) {
                        builders.add(EntryBuilder(fragment.left, fragment.centerY))
                        builders.last().nameParts.add(fragment.text.trim())
                    } else {
                        existing.addName(fragment.text)
                    }
                }
            }
        }

        result += finalizeBuilders(builders, dayIndex, periodRows)
        return result
    }

    private fun finalizeBuilders(
        builders: List<EntryBuilder>,
        dayIndex: Int,
        periodRows: Map<Int, Float>
    ): List<Course> {
        if (builders.isEmpty()) return emptyList()
        val courseBuilders = builders.filter { it.nameParts.isNotEmpty() }
        if (courseBuilders.isEmpty()) return emptyList()

        return courseBuilders.mapNotNull { builder ->
            val name = normalizeCourseName(builder.nameParts.joinToString(""))
            if (name.isBlank()) return@mapNotNull null
            val (start, end) = resolvePeriods(builder, periodRows)
            Course(
                name = name,
                teacher = builder.teacherParts.joinToString(" ").trim(),
                weeks = normalizeDisplay(builder.weeks.orEmpty()),
                location = builder.location?.trim().orEmpty(),
                dayIndex = dayIndex,
                startPeriod = start,
                endPeriod = end,
                rawLines = emptyList()
            )
        }
    }

    private fun EntryBuilder.addName(part: String) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return
        val previous = nameParts.lastOrNull()
        when {
            previous != null && trimmed.matches(Regex("\\d{1,3}")) && previous.endsWith("（一") ->
                nameParts.add("）$trimmed")
            previous != null && trimmed.matches(Regex("\\d{1,3}")) ->
                nameParts.add(" $trimmed")
            else -> nameParts.add(trimmed)
        }
    }

    private fun resolvePeriods(builder: EntryBuilder, periodRows: Map<Int, Float>): Pair<Int, Int> {
        val normalized = normalize(builder.periods.orEmpty())
        val range = PERIOD_RANGE_REGEX.find(normalized)
        if (range != null) {
            val a = range.groupValues[1].toIntOrNull()
            val b = range.groupValues[2].toIntOrNull()
            if (a != null && b != null) return a.coerceAtLeast(1) to b.coerceAtLeast(a)
        }
        val single = SINGLE_PERIOD_REGEX.find(normalized)
        if (single != null) {
            val p = single.groupValues[1].toIntOrNull()
            if (p != null) return p.coerceAtLeast(1) to p.coerceAtLeast(1)
        }
        val nearest = periodRows.entries.minByOrNull { abs(it.value - builder.firstY) }?.key ?: 1
        return nearest.coerceAtLeast(1) to nearest.coerceAtLeast(1)
    }

    private fun splitRepeated(line: OcrLine): List<Fragment> {
        val elements = line.elements
        if (elements.size < 6) return listOf(fragmentFrom(elements, line))

        val text = elements.joinToString("") { it.text }
        if (text.length < 8) return listOf(fragmentFrom(elements, line))

        var cumulative = 0
        val boundaries = elements.mapIndexed { index, element ->
            cumulative += element.text.length
            index + 1 to cumulative
        }

        var bestElementCount = 0
        var bestScore = 0.0
        val maxHalf = text.length / 2
        for ((elementCount, charCount) in boundaries) {
            if (charCount < 4 || charCount > maxHalf || charCount * 2 > text.length) continue
            val left = text.substring(0, charCount)
            val right = text.substring(charCount, charCount * 2)
            val score = similarity(left, right)
            if (score >= 0.78 && score > bestScore) {
                bestScore = score
                bestElementCount = elementCount
            }
        }

        if (bestElementCount <= 0) return listOf(fragmentFrom(elements, line))
        return listOf(
            fragmentFrom(elements.subList(0, bestElementCount), line, startsColumn = true),
            fragmentFrom(elements.subList(bestElementCount, elements.size), line, startsColumn = true)
        )
    }

    private fun fragmentFrom(
        elements: List<OcrElement>,
        line: OcrLine,
        startsColumn: Boolean = false
    ): Fragment {
        val text = elements.joinToString("") { it.text }
        return Fragment(
            text = text,
            left = elements.minOfOrNull { it.left } ?: line.left,
            top = elements.minOfOrNull { it.top } ?: line.top,
            right = elements.maxOfOrNull { it.right } ?: line.right,
            bottom = elements.maxOfOrNull { it.bottom } ?: line.bottom,
            startsColumn = startsColumn
        )
    }

    private fun similarity(a: String, b: String): Double {
        val n = min(a.length, b.length)
        if (n == 0) return 0.0
        var matches = 0
        for (i in 0 until n) {
            if (a[i] == b[i]) matches++
        }
        return matches.toDouble() / n
    }

    private fun isDayHeader(line: OcrLine): Boolean {
        return DAY_HEADER_REGEX.matchEntire(line.text.trim()) != null
    }

    private fun isPeriodLabel(line: OcrLine): Boolean {
        return PERIOD_LABEL_REGEX.find(line.text) != null
    }

    private fun isWeeks(text: String): Boolean {
        return WEEKS_REGEX.matches(text)
    }

    private fun isPeriods(text: String): Boolean {
        return PERIOD_RANGE_REGEX.matches(text) || SINGLE_PERIOD_REGEX.matches(text)
    }

    private fun isLocation(text: String): Boolean {
        return LOCATION_KEYWORDS.any { text.contains(it) } && text.length >= 4
    }

    private fun normalize(text: String): String {
        return text
            .replace(Regex("(?<=\\d)\\s*[—－–~一-]\\s*(?=\\d)"), "-")
            .replace("：", ":")
            .replace("·", "-")
            .replace(Regex("\\s+"), "")
    }

    private fun normalizeDisplay(text: String): String {
        return normalize(text)
    }

    private fun normalizeCourseName(name: String): String {
        val trimmed = name.trim().replace(Regex("\\s+"), " ")
        if (trimmed.contains("（一）") || trimmed.contains("(一)")) {
            return trimmed
                .replace(Regex("(?:[-_＿]?|一?)\\d{2}$"), "")
                .replace(Regex("\\s+"), "")
                .trim()
        }
        val spaceMatch = COURSE_SECTION_SPACE_REGEX.find(trimmed)
        if (spaceMatch != null) {
            return "${spaceMatch.groupValues[1]}（一）${spaceMatch.groupValues[2]}"
        }
        val match = COURSE_SECTION_REGEX.find(trimmed)
        return if (match != null) {
            "${match.groupValues[1]}（一）${match.groupValues[3]}"
        } else {
            trimmed
        }
    }

    companion object {
        private val DAY_HEADER_REGEX = Regex("^(?:星期|周)\\s*([一二三四五六日])\\s*$")
        private val PERIOD_LABEL_REGEX = Regex("第\\s*(\\d{1,2})\\s*节")
        private val PERIOD_RANGE_REGEX =
            Regex("^\\s*(?:第\\s*)?(\\d{1,2})\\s*[-—－–~一至]\\s*(\\d{1,2})\\s*节?\\s*$")
        private val SINGLE_PERIOD_REGEX = Regex("^\\s*第\\s*(\\d{1,2})\\s*节\\s*$")
        private val WEEKS_REGEX = Regex(
            "^\\s*(?:(?:第\\s*)?\\d{1,2}\\s*[-—－–~一]\\s*\\d{1,2}\\s*周|第\\s*\\d{1,2}\\s*周|单周|双周)\\s*$"
        )
        private val LOCATION_KEYWORDS = listOf("校区", "楼", "教室", "实验室", "中心", "场", "室")
        private val COURSE_SECTION_REGEX = Regex("^(.+?)(一)(\\d{2})$")
        private val COURSE_SECTION_SPACE_REGEX = Regex("^(.+?)\\s+(\\d{2})$")
        private val TIME_PATTERN = Regex("^\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}$")
    }
}
