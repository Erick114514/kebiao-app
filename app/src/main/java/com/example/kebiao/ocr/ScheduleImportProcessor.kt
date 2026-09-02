package com.example.kebiao.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.kebiao.model.ScheduleParseResult

class ScheduleImportProcessor(private val context: Context) {

    private val pdfProcessor = PdfImportProcessor(context)
    private val docProcessor = DocImportProcessor(context)

    suspend fun processUri(uri: Uri): ScheduleParseResult {
        val displayName = queryDisplayName(uri)
        val suffix = displayName
            .ifBlank { uri.lastPathSegment.orEmpty() }
            .substringAfterLast('.', "")
            .lowercase()
        val mime = context.contentResolver.getType(uri)
        return when {
            suffix == "pdf" || mime == "application/pdf" -> pdfProcessor.processUri(uri)
            suffix == "doc" || suffix == "docx" ||
                mime == "application/msword" || mime == DOCX_MIME -> docProcessor.processUri(uri)
            else -> error("仅支持 PDF、DOC、DOCX 文件")
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
    }
}
