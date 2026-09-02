package com.example.kebiao.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.kebiao.model.ScheduleParseResult
import com.example.kebiao.parser.DocCourseParser
import com.example.kebiao.parser.DocxTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.File
import java.util.zip.ZipFile

class DocImportProcessor(private val context: Context) {

    suspend fun processUri(uri: Uri): ScheduleParseResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val suffix = displayName
            .ifBlank { uri.lastPathSegment.orEmpty() }
            .substringAfterLast('.', "")
            .lowercase()
        val mime = context.contentResolver.getType(uri)
        when {
            suffix == "doc" || mime == "application/msword" -> processDoc(uri)
            suffix == "docx" || mime == DOCX_MIME -> processDocx(uri)
            else -> error("仅支持 DOC、DOCX 格式的文档")
        }
    }

    private fun processDocx(uri: Uri): ScheduleParseResult {
        return withTempFile(uri, "docx") { file ->
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: error("不是有效的 DOCX 文件")
                val lines = DocxTextExtractor().extract(zip.getInputStream(entry))
                DocCourseParser().parse(lines)
            }
        }
    }

    private fun processDoc(uri: Uri): ScheduleParseResult {
        return withTempFile(uri, "doc") { file ->
            try {
                val fs = POIFSFileSystem(file, true)
            try {
                parseDocFile(fs)
            } finally {
                fs.close()
            }
            } catch (e: Exception) {
                error("无法读取 DOC 文件：${e.message}")
            }
        }
    }

    private fun parseDocFile(fs: POIFSFileSystem): ScheduleParseResult {
        val extractor = WordExtractor(fs)
        try {
            val cells = mutableListOf<String>()
            var current = StringBuilder()
            for (paragraph in extractor.paragraphText) {
                val parts = paragraph.split('\u0007')
                if (parts.isNotEmpty()) current.append(parts[0])
                for (i in 1 until parts.size) {
                    cells.add(current.toString().replace(Regex("[ \\t]+"), " ").trim())
                    current = StringBuilder(parts[i])
                }
            }
            val last = current.toString().replace(Regex("[ \\t]+"), " ").trim()
            if (last.isNotBlank()) cells.add(last)

            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            for (cell in cells) {
                if (PERIOD_LABEL_REGEX.containsMatchIn(cell) && row.isNotEmpty()) {
                    rows.add(row)
                    row = mutableListOf()
                }
                row.add(cell)
            }
            if (row.isNotEmpty()) rows.add(row)
            return DocCourseParser().parse(rows.map { it.joinToString("\t") })
        } finally {
            extractor.close()
        }
    }

    private fun <T> withTempFile(uri: Uri, suffix: String, block: (File) -> T): T {
        val file = File(context.cacheDir, "kebiao_${System.currentTimeMillis()}.$suffix")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            return block(file)
        } finally {
            file.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
        return ""
    }

    companion object {
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val PERIOD_LABEL_REGEX = Regex("第\\s*\\d{1,2}\\s*节")
    }
}
