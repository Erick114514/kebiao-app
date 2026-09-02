package com.example.kebiao.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import com.example.kebiao.model.ScheduleParseResult
import com.example.kebiao.parser.OcrElement
import com.example.kebiao.parser.OcrLine
import com.example.kebiao.parser.PdfCourseParser
import com.example.kebiao.parser.PdfTextLayoutExtractor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PdfImportProcessor(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun processUri(uri: Uri): ScheduleParseResult = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "kebiao_${System.currentTimeMillis()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            processPdfFile(file)
        } finally {
            file.delete()
        }
    }

    private fun processPdfFile(file: File): ScheduleParseResult {
        tryExtractText(file)?.let { return it }
        return processPdfWithOcr(file)
    }

    private fun tryExtractText(file: File): ScheduleParseResult? {
        return try {
            val document = PDDocument.load(file)
            try {
                val layouts = PdfTextLayoutExtractor().extract(document)
                if (layouts.isEmpty()) return null
                val hasTimetableEvidence = layouts.any { layout ->
                    layout.lines.any { DAY_HEADER_REGEX.matches(it.text.trim()) } &&
                        layout.lines.any { PERIOD_LABEL_REGEX.containsMatchIn(it.text) }
                }
                if (!hasTimetableEvidence) return null
                val results = layouts.map { layout ->
                    PdfCourseParser().parse(layout.lines, layout.width, layout.height)
                }
                val courses = results
                    .flatMap { it.courses }
                    .distinctBy {
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
                if (courses.size < 3) return null
                val periodTimes = results
                    .firstOrNull { it.periodTimes.isNotEmpty() }
                    ?.periodTimes
                    ?: DEFAULT_PERIOD_TIMES
                ScheduleParseResult(
                    courses = courses,
                    periodTimes = periodTimes,
                    warnings = results.flatMap { it.warnings }
                )
            } finally {
                document.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun processPdfWithOcr(file: File): ScheduleParseResult {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        val allLines = mutableListOf<OcrLine>()
        var pageWidth = 0f
        var pageHeight = 0f
        try {
            for (pageIndex in 0 until renderer.pageCount) {
                val page = renderer.openPage(pageIndex)
                try {
                    val scale = min(4f, 3200f / max(page.width, page.height))
                    val width = (page.width * scale).toInt().coerceAtLeast(1000)
                    val height = (page.height * scale).toInt().coerceAtLeast(1000)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        postScale(width / page.width.toFloat(), height / page.height.toFloat())
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageWidth = width.toFloat()
                    pageHeight = height.toFloat()
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val text = Tasks.await(recognizer.process(image))
                    bitmap.recycle()
                    allLines += toOcrLines(text)
                } finally {
                    page.close()
                }
            }
        } finally {
            recognizer.close()
            renderer.close()
            descriptor.close()
        }
        return PdfCourseParser().parse(allLines, pageWidth, pageHeight)
    }

    private fun toOcrLines(text: Text): List<OcrLine> {
        val lines = mutableListOf<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements.map { element ->
                    val box = element.boundingBox ?: line.boundingBox ?: return@map OcrElement(
                        text = element.text,
                        left = 0f,
                        top = 0f,
                        right = 0f,
                        bottom = 0f
                    )
                    OcrElement(
                        text = element.text,
                        left = box.left.toFloat(),
                        top = box.top.toFloat(),
                        right = box.right.toFloat(),
                        bottom = box.bottom.toFloat()
                    )
                }
                lines += OcrLine(line.text, elements)
            }
        }
        return lines
    }

    companion object {
        private val DAY_HEADER_REGEX = Regex("^(?:星期|周)\\s*[一二三四五六日]$")
        private val PERIOD_LABEL_REGEX = Regex("第\\s*\\d{1,2}\\s*节")
    }
}
