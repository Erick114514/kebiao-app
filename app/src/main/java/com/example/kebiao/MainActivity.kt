package com.example.kebiao

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.kebiao.model.Course
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import com.example.kebiao.ocr.ScheduleImportProcessor
import com.example.kebiao.ui.TimetableView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var processor: ScheduleImportProcessor
    private lateinit var progressLayout: View
    private lateinit var progressText: TextView
    private lateinit var emptyLayout: View
    private lateinit var gridScroll: View
    private lateinit var summaryBar: View
    private lateinit var summaryText: TextView
    private lateinit var clearButton: Button
    private lateinit var timetable: TimetableView

    private val currentCourses = mutableListOf<Course>()
    private var currentPeriodTimes = DEFAULT_PERIOD_TIMES

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::startImport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        processor = ScheduleImportProcessor(this)

        progressLayout = findViewById(R.id.progressLayout)
        progressText = findViewById(R.id.progressText)
        emptyLayout = findViewById(R.id.emptyLayout)
        gridScroll = findViewById(R.id.gridScroll)
        summaryBar = findViewById(R.id.summaryBar)
        summaryText = findViewById(R.id.summaryText)
        clearButton = findViewById(R.id.btnClear)
        timetable = findViewById(R.id.timetable)
        timetable.onCourseClick = ::showEditCourseDialog

        findViewById<Button>(R.id.btnManual).setOnClickListener { showManualInputDialog() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { launchDocumentPicker() }
        findViewById<Button>(R.id.btnEmptyManual).setOnClickListener { showManualInputDialog() }
        findViewById<Button>(R.id.btnEmptyImport).setOnClickListener { launchDocumentPicker() }
        clearButton.setOnClickListener { clearSchedule() }
    }

    private fun launchDocumentPicker() {
        openDocument.launch(
            arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    private fun startImport(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true, "正在解析课表文件…")
            val result = runCatching { processor.processUri(uri) }
            result.onSuccess {
                currentCourses.clear()
                currentCourses.addAll(it.courses)
                if (it.periodTimes.isNotEmpty()) {
                    currentPeriodTimes = it.periodTimes
                }
                renderSchedule(it.warnings)
            }
            result.onFailure {
                setBusy(false, "")
                if (currentCourses.isEmpty()) {
                    emptyLayout.isVisible = true
                    gridScroll.isVisible = false
                    summaryBar.isVisible = false
                }
                Toast.makeText(this@MainActivity, "解析失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManualInputDialog() {
        val state = createManualInputState()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.manual_input)
            .setView(state.view)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = state.name.text.toString().trim()
                if (name.isEmpty()) {
                    state.name.error = getString(R.string.name_required)
                    return@setOnClickListener
                }
                val start = state.start.selectedItemPosition + 1
                var end = state.end.selectedItemPosition + 1
                if (end < start) {
                    state.end.setSelection(start - 1)
                    end = start
                }

                currentCourses += Course(
                    name = name,
                    teacher = state.teacher.text.toString().trim(),
                    weeks = state.weeks.text.toString().trim(),
                    location = state.location.text.toString().trim(),
                    dayIndex = state.day.selectedItemPosition,
                    startPeriod = start,
                    endPeriod = end
                )
                renderSchedule()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditCourseDialog(course: Course) {
        val state = createManualInputState()
        state.name.setText(course.name)
        state.teacher.setText(course.teacher)
        state.location.setText(course.location)
        state.weeks.setText(course.weeks)
        state.day.setSelection(course.dayIndex.coerceIn(0, dayLabels.size - 1))
        state.start.setSelection((course.startPeriod - 1).coerceIn(0, periodLabels.size - 1))
        state.end.setSelection((course.endPeriod - 1).coerceIn(0, periodLabels.size - 1))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_course)
            .setView(state.view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = state.name.text.toString().trim()
                if (name.isEmpty()) {
                    state.name.error = getString(R.string.name_required)
                    return@setOnClickListener
                }
                val start = state.start.selectedItemPosition + 1
                var end = state.end.selectedItemPosition + 1
                if (end < start) {
                    state.end.setSelection(start - 1)
                    end = start
                }
                val index = currentCourses.indexOfFirst { it === course }
                if (index < 0) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                currentCourses[index] = Course(
                    name = name,
                    teacher = state.teacher.text.toString().trim(),
                    weeks = state.weeks.text.toString().trim(),
                    location = state.location.text.toString().trim(),
                    dayIndex = state.day.selectedItemPosition,
                    startPeriod = start,
                    endPeriod = end
                )
                renderSchedule()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun createManualInputState(): ManualInputState {
        val view = layoutInflater.inflate(R.layout.dialog_manual_input, null)
        val name = view.findViewById<EditText>(R.id.etName)
        val teacher = view.findViewById<EditText>(R.id.etTeacher)
        val location = view.findViewById<EditText>(R.id.etLocation)
        val weeks = view.findViewById<EditText>(R.id.etWeeks)
        val day = view.findViewById<Spinner>(R.id.spDay)
        val start = view.findViewById<Spinner>(R.id.spStart)
        val end = view.findViewById<Spinner>(R.id.spEnd)

        day.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            dayLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        start.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            periodLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        end.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            periodLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return ManualInputState(view, name, teacher, location, weeks, day, start, end)
    }

    private fun clearSchedule() {
        currentCourses.clear()
        currentPeriodTimes = DEFAULT_PERIOD_TIMES
        renderSchedule()
    }

    private fun renderSchedule(warnings: List<String> = emptyList()) {
        setBusy(false, "")
        if (currentCourses.isEmpty()) {
            emptyLayout.isVisible = true
            gridScroll.isVisible = false
            summaryBar.isVisible = false
            return
        }

        emptyLayout.isVisible = false
        gridScroll.isVisible = true
        summaryBar.isVisible = true
        clearButton.isVisible = true
        timetable.setSchedule(currentCourses, currentPeriodTimes)

        val warningText = if (warnings.isEmpty()) {
            ""
        } else {
            "\n" + warnings.joinToString("\n")
        }
        summaryText.text = getString(
            R.string.schedule_summary,
            currentCourses.size
        ) + warningText
    }

    private data class ManualInputState(
        val view: View,
        val name: EditText,
        val teacher: EditText,
        val location: EditText,
        val weeks: EditText,
        val day: Spinner,
        val start: Spinner,
        val end: Spinner
    )

    private fun setBusy(busy: Boolean, message: String) {
        progressLayout.isVisible = busy
        if (busy) {
            progressText.text = message
            findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        }
    }

    companion object {
        private val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        private val periodLabels = (1..13).map { "第${it}节" }
    }
}
