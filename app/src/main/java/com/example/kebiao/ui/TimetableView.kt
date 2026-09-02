package com.example.kebiao.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.kebiao.model.Course
import kotlin.math.max
import kotlin.math.min

class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val labelWidth = dp(84f)
    private val dayWidth = dp(180f)
    private val headerHeight = dp(48f)
    private val rowHeight = dp(104f)
    private val pad = dp(7f)
    private val gap = dp(2f)

    private var courses: List<Course> = emptyList()
    private var periodTimes: List<String> = emptyList()
    private var placed: List<PlacedCourse> = emptyList()
    var onCourseClick: ((Course) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.rgb(214, 219, 226)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(239, 243, 248)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(62, 70, 82)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 38, 46)
        textSize = sp(14f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 31, 39)
        textSize = sp(12f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(54, 63, 74)
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val cardRect = RectF()

    fun setSchedule(newCourses: List<Course>, newPeriodTimes: List<String>) {
        courses = newCourses
        periodTimes = newPeriodTimes
        placed = layoutCourses()
        requestLayout()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val course = placed.firstOrNull {
                event.x >= it.left && event.x <= it.right && event.y >= it.top && event.y <= it.bottom
            }?.course
            if (course != null) {
                onCourseClick?.invoke(course)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private val periodCount: Int
        get() = max(13, max(periodTimes.size, courses.maxOfOrNull { it.endPeriod } ?: 13))

    private fun layoutCourses(): List<PlacedCourse> {
        val result = mutableListOf<PlacedCourse>()
        for (day in 0 until 7) {
            val dayCourses = courses
                .filter { it.dayIndex == day }
                .sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
            if (dayCourses.isEmpty()) continue

            val colors = IntArray(dayCourses.size) { -1 }
            for (i in dayCourses.indices) {
                val used = mutableSetOf<Int>()
                for (j in 0 until i) {
                    if (overlaps(dayCourses[i], dayCourses[j])) used.add(colors[j])
                }
                var color = 0
                while (color in used) color++
                colors[i] = color
            }

            val columnCount = max(1, (colors.maxOrNull() ?: 0) + 1)
            val cardWidth = (dayWidth - pad * 2f) / columnCount
            for (i in dayCourses.indices) {
                val course = dayCourses[i]
                val startPeriod = course.startPeriod.coerceIn(1, periodCount)
                val endPeriod = course.endPeriod.coerceIn(startPeriod, periodCount)
                val left = labelWidth + day * dayWidth + pad + colors[i] * cardWidth
                val right = left + cardWidth - gap
                val top = headerHeight + (startPeriod - 1) * rowHeight + pad
                val bottom = headerHeight + endPeriod * rowHeight - pad
                result += PlacedCourse(course, left, top, right, bottom)
            }
        }
        return result
    }

    private fun overlaps(a: Course, b: Course): Boolean {
        return a.startPeriod <= b.endPeriod && b.startPeriod <= a.endPeriod
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (labelWidth + dayWidth * 7 + pad * 2f).toInt()
        val height = (headerHeight + rowHeight * periodCount + pad * 2f).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(247, 248, 250))

        val totalWidth = labelWidth + dayWidth * 7
        val totalHeight = headerHeight + rowHeight * periodCount

        canvas.drawRect(0f, 0f, totalWidth, headerHeight, headerPaint)

        // Grid lines
        canvas.drawLine(labelWidth, 0f, labelWidth, totalHeight, gridPaint)
        for (day in 0..7) {
            val x = labelWidth + day * dayWidth
            canvas.drawLine(x, 0f, x, totalHeight, gridPaint)
        }
        canvas.drawLine(0f, headerHeight, totalWidth, headerHeight, gridPaint)
        for (period in 0..periodCount) {
            val y = headerHeight + period * rowHeight
            canvas.drawLine(0f, y, totalWidth, y, gridPaint)
        }

        // Day headers
        for (day in 0 until 7) {
            val cx = labelWidth + day * dayWidth + dayWidth / 2f
            val cy = headerHeight / 2f - dayPaint.textSize * 0.15f
            canvas.drawText(dayNames[day], cx, cy, dayPaint)
        }

        // Period labels
        for (period in 0 until periodCount) {
            val cy = headerHeight + period * rowHeight + rowHeight / 2f
            val lines = periodTimes.getOrElse(period) { "第${period + 1}节" }.split("\n")
            val lineHeight = labelPaint.textSize * 1.12f
            val startY = cy - (lines.size - 1) * lineHeight / 2f + labelPaint.textSize * 0.35f
            for ((i, text) in lines.withIndex()) {
                canvas.drawText(text, labelWidth / 2f, startY + i * lineHeight, labelPaint)
            }
        }

        for (item in placed) {
            drawCourseCard(canvas, item)
        }
    }

    private fun drawCourseCard(canvas: Canvas, item: PlacedCourse) {
        val color = dayColors[item.course.dayIndex.coerceIn(0, 6)]
        cardRect.set(item.left, item.top, item.right, item.bottom)
        val radius = dp(8f)
        cardPaint.color = withAlpha(color, 0xEC)
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        cardStrokePaint.color = withAlpha(color, 0xFF)
        canvas.drawRoundRect(cardRect, radius, radius, cardStrokePaint)

        val contentLeft = item.left + dp(9f)
        val maxWidth = item.right - item.left - dp(18f)
        var y = item.top + dp(10f)
        val availableHeight = item.bottom - item.top - dp(18f)
        val lineHeight = infoPaint.textSize * 1.18f
        var remainingLines = max(1, (availableHeight / lineHeight).toInt())

        val name = drawWrapped(
            canvas,
            item.course.displayName,
            namePaint,
            contentLeft,
            y,
            maxWidth,
            min(2, remainingLines)
        )
        y += name.height + dp(2f)
        remainingLines -= name.lineCount

        if (item.course.teacher.isNotBlank() && remainingLines > 0) {
            val teacher = drawWrapped(
                canvas,
                "老师：${item.course.teacher}",
                infoPaint,
                contentLeft,
                y,
                maxWidth,
                min(2, remainingLines)
            )
            y += teacher.height + dp(1f)
            remainingLines -= teacher.lineCount
        }

        if (item.course.location.isNotBlank() && remainingLines > 0) {
            val location = drawWrapped(
                canvas,
                "教室：${item.course.location}",
                infoPaint,
                contentLeft,
                y,
                maxWidth,
                min(2, remainingLines)
            )
            y += location.height + dp(1f)
            remainingLines -= location.lineCount
        }

        if (item.course.weeks.isNotBlank() && remainingLines > 0) {
            drawWrapped(
                canvas,
                "周次：${item.course.weeks}",
                infoPaint,
                contentLeft,
                y,
                maxWidth,
                remainingLines
            )
        }
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        left: Float,
        top: Float,
        maxWidth: Float,
        maxLines: Int
    ): WrappedResult {
        if (text.isBlank() || maxLines <= 0) return WrappedResult(0f, 0)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .build()
        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
        return WrappedResult(layout.height.toFloat(), layout.lineCount)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private data class PlacedCourse(
        val course: Course,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class WrappedResult(val height: Float, val lineCount: Int)

    companion object {
        private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        private val dayColors = intArrayOf(
            Color.rgb(14, 143, 140),
            Color.rgb(67, 97, 238),
            Color.rgb(231, 111, 81),
            Color.rgb(82, 183, 136),
            Color.rgb(214, 69, 141),
            Color.rgb(77, 150, 255),
            Color.rgb(155, 93, 229)
        )
    }
}
