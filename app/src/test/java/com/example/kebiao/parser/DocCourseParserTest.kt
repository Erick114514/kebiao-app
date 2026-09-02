package com.example.kebiao.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocCourseParserTest {

    @Test
    fun `parses complex multi teacher lab cell`() {
        val result = DocCourseParser().parse(
            listOf(
                "节次/时间\t星期一\t星期二\t星期三\t星期四\t星期五\t星期六\t星期日",
                "第11节\t\t\t模拟电路实验\n-03\n张宇识*胡丙萌 5-10周\n11-13节\n丰台校区崇理 楼崇理414\t\t\t\t\t"
            )
        )

        val course = result.courses.first { it.name.contains("模拟电路实验") }
        assertEquals(2, course.dayIndex)
        assertEquals(11, course.startPeriod)
        assertEquals(13, course.endPeriod)
        assertTrue(course.teacher.contains("张宇识"))
        assertTrue(course.teacher.contains("胡丙萌"))
        assertTrue(course.location.contains("崇理414"))
    }

    @Test
    fun `parses transposed timetable by day columns`() {
        val result = DocCourseParser().parse(
            listOf(
                "节次/时间\t星期一\t星期二\t星期三\t星期四\t星期五\t星期六\t星期日",
                "第1节\t大学英语\n文华俊\n1-16周\n1-2节\n日新楼A303\t\t\t\t\t\t\t",
                "第3节\t\t高等数学\n陈明\n1-16周\n3-4节\n教学楼101\t\t\t\t\t\t"
            )
        )

        assertEquals(
            "courses=${result.courses.joinToString { it.name + "@" + it.dayIndex }}",
            2,
            result.courses.size
        )

        val english = result.courses.first { it.name.contains("大学英语") }
        assertEquals(0, english.dayIndex)
        assertEquals(1, english.startPeriod)
        assertEquals(2, english.endPeriod)
        assertTrue(english.teacher.contains("文华俊"))

        val math = result.courses.first { it.name.contains("高等数学") }
        assertEquals(1, math.dayIndex)
        assertEquals(3, math.startPeriod)
        assertEquals(4, math.endPeriod)
        assertTrue(math.teacher.contains("陈明"))
    }

    @Test
    fun `parses table rows with day period weeks teacher and location`() {
        val result = DocCourseParser().parse(
            listOf(
                "星期一\t数字电路与逻辑设计\t蒋惠萍*\t1-18周\t第3-5节\t丰台校区博文楼博文301",
                "星期二\t大学英语\t文华俊\t1-18周\t6-7节\t日新楼日新A303"
            )
        )

        assertEquals(2, result.courses.size)
        assertEquals(13, result.periodTimes.size)

        val circuit = result.courses[0]
        assertEquals(0, circuit.dayIndex)
        assertEquals(3, circuit.startPeriod)
        assertEquals(5, circuit.endPeriod)
        assertTrue(circuit.name.contains("数字电路"))
        assertTrue(circuit.teacher.contains("蒋惠萍"))
        assertTrue(circuit.weeks.contains("1-18周"))
        assertTrue(circuit.location.contains("博文楼"))

        val english = result.courses[1]
        assertEquals(1, english.dayIndex)
        assertEquals(6, english.startPeriod)
        assertEquals(7, english.endPeriod)
        assertTrue(english.name.contains("大学英语"))
        assertTrue(english.teacher.contains("文华俊"))
    }

    @Test
    fun `uses previous day when the row has no day cell`() {
        val result = DocCourseParser().parse(
            listOf(
                "星期一\t高等数学\t陈明*\t1-16周\t1-2节\t教学楼101",
                "线性代数\t王芳\t1-16周\t3-4节\t教学楼202"
            )
        )

        assertEquals(2, result.courses.size)
        assertTrue(result.courses.all { it.dayIndex == 0 })
    }
}
