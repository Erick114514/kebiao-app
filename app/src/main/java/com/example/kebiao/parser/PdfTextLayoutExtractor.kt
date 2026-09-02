package com.example.kebiao.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.max

class PdfTextLayoutExtractor {

    fun extract(document: PDDocument): List<PageLayout> {
        val stripper = PositionStripper()
        stripper.sortByPosition = true
        stripper.writeText(document, StringWriter())
        return stripper.pages
    }

    private inner class PositionStripper : PDFTextStripper() {

        val pages = mutableListOf<PageLayout>()
        private val positions = mutableListOf<TextPosition>()
        private var currentPageNumber = 0

        override fun startPage(page: PDPage) {
            super.startPage(page)
            currentPageNumber = getCurrentPageNo()
            positions.clear()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            positions.addAll(textPositions)
        }

        override fun endPage(page: PDPage) {
            if (positions.isNotEmpty()) {
                val box = page.mediaBox
                pages += PageLayout(
                    pageIndex = currentPageNumber - 1,
                    lines = buildLines(positions),
                    width = box.width,
                    height = box.height
                )
            }
            super.endPage(page)
        }
    }

    private fun buildLines(positions: List<TextPosition>): List<OcrLine> {
        val sorted = positions.sortedWith(compareBy({ it.yDirAdj }, { it.xDirAdj }))
        val rows = mutableListOf<List<TextPosition>>()
        var current = mutableListOf<TextPosition>()
        for (position in sorted) {
            if (current.isEmpty() || abs(position.yDirAdj - current.first().yDirAdj) <= 4f) {
                current.add(position)
            } else {
                rows.add(current)
                current = mutableListOf(position)
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows.flatMap { buildRowLines(it) }
    }

    private fun buildRowLines(row: List<TextPosition>): List<OcrLine> {
        val sorted = row.sortedBy { it.xDirAdj }
        val rowText = sorted.joinToString(" ") { it.unicode.trim() }.trim()
        val isLabelRow = DAY_HEADER_REGEX.matches(rowText) ||
            PERIOD_LABEL_REGEX.containsMatchIn(rowText)
        if (isLabelRow) {
            return listOfNotNull(toLine(sorted))
        }

        val fragments = mutableListOf<MutableList<TextPosition>>()
        for (position in sorted) {
            val last = fragments.lastOrNull()?.lastOrNull()
            if (last == null ||
                position.xDirAdj - (last.xDirAdj + last.widthDirAdj) <= gapThreshold(position)
            ) {
                if (fragments.isEmpty()) fragments.add(mutableListOf())
                fragments.last().add(position)
            } else {
                fragments.add(mutableListOf(position))
            }
        }
        return fragments.mapNotNull { toLine(it) }
    }

    private fun gapThreshold(position: TextPosition): Float {
        return max(6f, position.widthOfSpace * 2.5f)
    }

    private fun toLine(positions: List<TextPosition>): OcrLine? {
        val sorted = positions.sortedBy { it.xDirAdj }
        val elements = sorted.mapNotNull { position ->
            val text = position.unicode.trim()
            if (text.isEmpty()) return@mapNotNull null
            OcrElement(
                text = text,
                left = position.xDirAdj,
                top = position.yDirAdj,
                right = position.xDirAdj + position.widthDirAdj,
                bottom = position.yDirAdj + position.heightDir
            )
        }
        if (elements.isEmpty()) return null
        return OcrLine(
            text = elements.joinToString(" ") { it.text },
            elements = elements
        )
    }

    data class PageLayout(
        val pageIndex: Int,
        val lines: List<OcrLine>,
        val width: Float,
        val height: Float
    )

    companion object {
        private val DAY_HEADER_REGEX = Regex("(?:星期|周)\\s*[一二三四五六日]")
        private val PERIOD_LABEL_REGEX = Regex("第\\s*\\d{1,2}\\s*节")
    }
}
