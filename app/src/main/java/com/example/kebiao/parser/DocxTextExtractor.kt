package com.example.kebiao.parser

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxTextExtractor {

    fun extract(input: InputStream): List<String> {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = builder.newDocumentBuilder().parse(input)
        val lines = mutableListOf<String>()
        extractNode(document.documentElement, lines)
        return lines
    }

    private fun extractNode(node: Node, lines: MutableList<String>) {
        when (localName(node)) {
            "tbl" -> {
                for (row in childElements(node, "tr")) {
                    val cells = childElements(row, "tc")
                        .mapNotNull { cell ->
                            cellText(cell).trim()
                        }
                        .filter { it.isNotBlank() }
                    if (cells.isNotEmpty()) {
                        lines.add(cells.joinToString("\t"))
                    }
                }
            }
            "p" -> {
                val text = textOf(node).replace(Regex("\\s+"), " ").trim()
                if (text.isNotBlank()) lines.add(text)
            }
            else -> {
                var child = node.firstChild
                while (child != null) {
                    extractNode(child, lines)
                    child = child.nextSibling
                }
            }
        }
    }

    private fun localName(node: Node): String? {
        return node.localName ?: node.nodeName.substringAfter(':')
    }

    private fun childElements(node: Node, name: String): List<Element> {
        val result = mutableListOf<Element>()
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && localName(child) == name) {
                result.add(child as Element)
            }
            child = child.nextSibling
        }
        return result
    }

    private fun textOf(node: Node): String {
        val builder = StringBuilder()

        fun collect(current: Node) {
            if (current.nodeType == Node.TEXT_NODE) {
                builder.append(current.nodeValue)
            }
            var child = current.firstChild
            while (child != null) {
                collect(child)
                child = child.nextSibling
            }
        }

        collect(node)
        return builder.toString()
    }

    private fun cellText(cell: Node): String {
        val paragraphs = childElements(cell, "p")
        if (paragraphs.isEmpty()) return textOf(cell)
        return paragraphs.joinToString("\n") { paragraph ->
            textOf(paragraph).replace(Regex("[ \\t]+"), " ").trim()
        }
    }
}
