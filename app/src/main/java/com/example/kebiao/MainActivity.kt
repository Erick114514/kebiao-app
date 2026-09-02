package com.example.kebiao

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.kebiao.model.ScheduleParseResult
import com.example.kebiao.ocr.PdfImportProcessor
import com.example.kebiao.ui.TimetableView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var processor: PdfImportProcessor
    private lateinit var progressLayout: View
    private lateinit var progressText: TextView
    private lateinit var emptyLayout: View
    private lateinit var gridScroll: View
    private lateinit var timetable: TimetableView
    private lateinit var summaryText: TextView

    private val openPdf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::startImport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        processor = PdfImportProcessor(this)

        progressLayout = findViewById(R.id.progressLayout)
        progressText = findViewById(R.id.progressText)
        emptyLayout = findViewById(R.id.emptyLayout)
        gridScroll = findViewById(R.id.gridScroll)
        timetable = findViewById(R.id.timetable)
        summaryText = findViewById(R.id.summaryText)

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            openPdf.launch(arrayOf("application/pdf", "application/octet-stream", "*/*"))
        }
        findViewById<Button>(R.id.btnSample).setOnClickListener { loadSample() }
        findViewById<Button>(R.id.btnEmptyImport).setOnClickListener {
            openPdf.launch(arrayOf("application/pdf", "application/octet-stream", "*/*"))
        }
    }

    private fun startImport(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true, "正在识别 PDF 课程信息…")
            val result = runCatching { processor.processUri(uri) }
            result.onSuccess(::showResult)
            result.onFailure {
                setBusy(false, "")
                emptyLayout.isVisible = true
                gridScroll.isVisible = false
                Toast.makeText(this@MainActivity, "解析失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSample() {
        lifecycleScope.launch {
            setBusy(true, "正在识别示例课表…")
            val result = runCatching { processor.processAsset("sample/sample.pdf") }
            result.onSuccess(::showResult)
            result.onFailure {
                setBusy(false, "")
                emptyLayout.isVisible = true
                gridScroll.isVisible = false
                Toast.makeText(this@MainActivity, "示例课表解析失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResult(result: ScheduleParseResult) {
        setBusy(false, "")
        emptyLayout.isVisible = false
        gridScroll.isVisible = true
        summaryText.isVisible = true
        timetable.setSchedule(result.courses, result.periodTimes)

        val warningText = if (result.warnings.isEmpty()) {
            ""
        } else {
            "\n" + result.warnings.joinToString("\n")
        }
        summaryText.text = "识别到 ${result.courses.size} 门课程，点击右上角可重新导入。$warningText"
    }

    private fun setBusy(busy: Boolean, message: String) {
        progressLayout.isVisible = busy
        if (busy) {
            progressText.text = message
            findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        }
    }
}
