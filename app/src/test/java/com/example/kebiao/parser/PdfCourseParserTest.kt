package com.example.kebiao.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class PdfCourseParserTest {

    @Test
    fun `merges split location fragments`() {
        fun line(text: String, x: Float, y: Float): OcrLine {
            val width = text.length * 12f
            return OcrLine(
                text,
                listOf(OcrElement(text, x, y, x + width, y + 30f))
            )
        }

        val lines = listOf(
            line("星期一", 300f, 10f),
            line("第1节", 80f, 100f),
            line("模拟电路实验", 300f, 100f),
            line("张宇识*胡丙萌", 300f, 140f),
            line("5-10周", 300f, 180f),
            line("11-13节", 300f, 220f),
            line("丰台校区崇理", 300f, 260f),
            line("楼崇理414", 420f, 260f)
        )

        val result = PdfCourseParser().parse(lines, 2000f, 2000f)

        assertEquals(1, result.courses.size)
        assertEquals("丰台校区崇理楼崇理414", result.courses[0].location)
        assertEquals(11, result.courses[0].startPeriod)
        assertEquals(13, result.courses[0].endPeriod)
    }

    @Test
    fun `parses explicit period range with leading 第 prefix`() {
        val lines = listOf(
            OcrLine(
                "星期一",
                listOf(OcrElement("星期一", 300f, 10f, 390f, 40f))
            ),
            OcrLine(
                "第1节",
                listOf(OcrElement("第1节", 80f, 100f, 200f, 130f))
            ),
            OcrLine(
                "数字电路",
                listOf(OcrElement("数字电路", 300f, 100f, 520f, 130f))
            ),
            OcrLine(
                "第3-5节",
                listOf(OcrElement("第3-5节", 300f, 140f, 440f, 170f))
            )
        )

        val result = PdfCourseParser().parse(lines, 2000f, 2000f)

        assertEquals(1, result.courses.size)
        assertEquals(0, result.courses[0].dayIndex)
        assertEquals(3, result.courses[0].startPeriod)
        assertEquals(5, result.courses[0].endPeriod)
    }

    @Test
    fun `parses ocr timetable into expected courses`() {
        val lines = readLines()
        val result = PdfCourseParser().parse(lines, 2932f, 2718f)

        assertTrue(
            "courses=${result.courses.size} warnings=${result.warnings.joinToString(" | ")}",
            result.courses.size >= 15
        )
        assertTrue(result.periodTimes.size >= 13)
        assertEquals("第1节\n08:00-08:45", result.periodTimes[0])
        assertEquals("第10节\n17:30-18:15", result.periodTimes[9])

        assertHas(result, "数字电路与逻辑设计", 0, 3, 5, "蒋惠萍")
        assertHas(result, "JAVA语言程序设计", 0, 6, 7, "闫晓东")
        assertHas(result, "数学物理方法", 0, 8, 10, "岑翼")
        assertHas(result, "中华民族共同体概论", 1, 1, 2, "王冬丽")
        assertHas(result, "大学体育", 1, 3, 4, "马辉")
        assertHas(result, "Python语言程序设计", 1, 6, 7, "郑睿")
        assertHas(result, "大学英语", 2, 6, 7, "文华俊")
        assertHas(result, "大学物理（一）下", 2, 8, 9, "邹斌")
        assertHas(result, "大学物理（一）", 3, 1, 2, "邹斌")
        assertHas(result, "概率论与数理统计", 3, 3, 5, "芮荣祥")
        assertHas(result, "数字电路与逻辑设计实验", 3, 8, 10, "苏骄阳")
        assertHas(result, "模拟电子线路", 4, 3, 5, "胡丙萌")
        assertHas(result, "意大利文化", 6, 1, 2, "网络教师")

        val javaCourse = result.courses.first { it.name.contains("JAVA语言程序设计") }
        assertTrue("java name should be normalized", javaCourse.name.contains("（一）01"))
        val circuitCourse = result.courses.first { it.name.contains("数字电路与逻辑设计") && it.dayIndex == 0 }
        assertTrue("circuit name should be normalized", circuitCourse.name.contains("（一）01"))

        val physicsLab = result.courses.filter { it.name.contains("大学物理实验") }
        assertEquals("physics lab should split into two week entries", 2, physicsLab.size)
        assertTrue(physicsLab.any { it.weeks.contains("3-4周") })
        assertTrue(physicsLab.any { it.weeks.contains("第5周") })

        val circuitLab = result.courses.filter { it.name.contains("模拟电路实验") }
        assertEquals("circuit lab should split into two week entries", 2, circuitLab.size)
        assertTrue(circuitLab.any { it.weeks.contains("5-10周") })
        assertTrue(circuitLab.any { it.weeks.contains("11-16周") })
    }

    private fun assertHas(
        result: com.example.kebiao.model.ScheduleParseResult,
        namePart: String,
        day: Int,
        start: Int,
        end: Int,
        teacherPart: String
    ) {
        val match = result.courses.firstOrNull {
            it.name.contains(namePart) && it.dayIndex == day &&
                it.startPeriod == start && it.endPeriod == end
        }
        assertTrue("missing $namePart day=$day periods=$start-$end", match != null)
        assertTrue(
            "teacher missing for $namePart: ${match?.teacher}",
            match!!.teacher.contains(teacherPart)
        )
    }

    private fun readLines(): List<OcrLine> {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr_lines.txt")
            ?: error("missing test resource")
        return InputStreamReader(stream, StandardCharsets.UTF_8)
            .readLines()
            .mapNotNull { raw ->
            val content = raw.removePrefix("L\t")
            val tab = content.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val rect = content.substring(0, tab).split(",")
            val text = content.substring(tab + 1)
            if (rect.size != 4) return@mapNotNull null
            val x = rect[0].toFloat()
            val y = rect[1].toFloat()
            val w = rect[2].toFloat()
            val h = rect[3].toFloat()
            val charWidth = if (text.isEmpty()) 1f else w / text.length
            val elements = text.mapIndexed { index, ch ->
                val left = x + index * charWidth
                OcrElement(
                    text = ch.toString(),
                    left = left,
                    top = y,
                    right = left + charWidth,
                    bottom = y + h
                )
            }
            OcrLine(text, elements)
        }
    }
}
