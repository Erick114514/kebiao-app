package com.example.kebiao.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {

    @Test
    fun `extracts table rows as tab separated lines`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>星期一</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>大学英语</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>文华俊*</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>1-16周</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>6-7节</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val zipInput = ZipInputStream(ByteArrayInputStream(docxBytes(xml)))
        var entry = zipInput.nextEntry
        while (entry != null && entry.name != "word/document.xml") {
            entry = zipInput.nextEntry
        }
        assertNotNull(entry)

        val lines = DocxTextExtractor().extract(zipInput)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("星期一"))
        assertTrue(lines[0].contains("大学英语"))
        assertTrue(lines[0].contains("\t6-7节"))
    }

    private fun docxBytes(xml: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
